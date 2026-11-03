package com.selectcode.sms.payroll.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.accounting.JournalWriter;
import com.selectcode.sms.shared.accounting.JournalWriter.Line;
import com.selectcode.sms.shared.attendance.StaffAttendanceSummary;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.Money;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monthly salary sheets (FR 6.8, 13.12, DECISIONS D-04).
 *
 * <p>Basic salary is the salary profile in effect on the 1st of the month (an increment dated mid-month applies from the
 * next month, QA-PAY-002). Deduction days = absent days + ½ × half days + ⌊late days ÷ late days per absence⌋ + leave
 * beyond the paid leave allowance, each counted only if the school's salary rules say so. Deduction = basic × deduction
 * days ÷ working days in the month, rounded once (HALF_UP, 2 dp). Earned = basic − deduction + allowance + lectures ×
 * per-lecture rate; net = earned − tax − other deductions.
 *
 * <p>DRAFT (editable) → APPROVED (journal: Dr salaries / Cr salaries payable, tax payable, salary deductions; locked) →
 * PAID (journal: Dr salaries payable / Cr cash or bank). Corrections only by reversing the sheet (QA-PAY-003).
 */
@Service
public class PayrollService {

	public record SheetRow(long id, String month, String status, int workingDays, int staffCount, String totalNet,
			String createdBy, Instant createdAt) {
	}

	public record LineView(long id, long staffId, String employeeNo, String staffName, String designation,
			String basic, String perLectureRate, int lectures, int workingDays, int absentDays, int halfDays,
			int lateDays, int leaveDays, String deductionDays, String attendanceDeduction, String allowance,
			String tax, String otherDeduction, String otherNote, String earned, String net, String attendanceSource,
			Map<String, Object> breakdown) {
	}

	public record SheetView(long id, String month, String status, int workingDays, String createdBy, Instant createdAt,
			String approvedBy, Instant approvedAt, String paidBy, Instant paidAt, Long paymentAccountId,
			String paymentAccountName, LocalDate paymentDate, String reversedBy, Instant reversedAt,
			String reversalReason, String totalEarned, String totalTax, String totalOther, String totalNet,
			List<LineView> lines, List<String> skipped, long version) {
	}

	public record LineInput(int absentDays, int halfDays, int lateDays, int leaveDays, int lectures, String allowance,
			String tax, String otherDeduction, String otherNote) {
	}

	private final JdbcTemplate jdbc;

	private final JournalWriter journal;

	private final SettingsService settings;

	private final WorkingDayCalendar workingDays;

	private final ObjectProvider<StaffAttendanceSummary> attendance;

	private final AuditService audit;

	private final JsonMapper json;

	private final Clock clock;

	PayrollService(JdbcTemplate jdbc, JournalWriter journal, SettingsService settings, WorkingDayCalendar workingDays,
			ObjectProvider<StaffAttendanceSummary> attendance, AuditService audit, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.journal = journal;
		this.settings = settings;
		this.workingDays = workingDays;
		this.attendance = attendance;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	// --------------------------------------------------------------- generate

	@Transactional
	public SheetView generate(String monthRaw) {
		YearMonth month = month(monthRaw);
		if (month.isAfter(YearMonth.from(MoneyInput.today(clock)))) {
			throw ApiException.businessRule("MONTH_IN_FUTURE", "Salary sheets can be made for this month or earlier");
		}
		LocalDate first = month.atDay(1);
		LocalDate last = month.atEndOfMonth();
		int days = workingDays.countWorkingDays(first, last);
		if (days <= 0) {
			throw ApiException.businessRule("NO_WORKING_DAYS", "This month has no working days in the calendar");
		}
		Long sheetId;
		try {
			sheetId = jdbc.queryForObject("""
					insert into salary_sheet (school_id, month, status, working_days, created_by)
					values (?, ?, 'DRAFT', ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
					month.toString(), days, CurrentActor.require().displayName());
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("SHEET_EXISTS", "There is already a salary sheet for " + month
					+ ". Open it, or reverse it to make a new one.");
		}
		List<String> skipped = new ArrayList<>();
		StaffAttendanceSummary source = attendance.getIfAvailable();
		for (Map<String, Object> s : jdbc.queryForList("""
				select s.id, s.name,
				       (select p.basic_salary from salary_profile p where p.staff_id = s.id and p.effective_from <= ?
				        order by p.effective_from desc limit 1) as basic,
				       (select p.per_lecture_rate from salary_profile p where p.staff_id = s.id and p.effective_from <= ?
				        order by p.effective_from desc limit 1) as rate
				from staff s
				where s.joining_date <= ? and (s.leaving_date is null or s.leaving_date >= ?)
				order by s.employee_no""", Date.valueOf(first), Date.valueOf(first), Date.valueOf(last),
				Date.valueOf(first))) {
			long staffId = ((Number) s.get("id")).longValue();
			BigDecimal basic = (BigDecimal) s.get("basic");
			if (basic == null) {
				skipped.add(s.get("name") + " (no salary recorded before " + first + ")");
				continue;
			}
			var totals = source == null ? java.util.Optional.<StaffAttendanceSummary.MonthTotals>empty()
					: source.approvedTotals(staffId, month);
			LineInput in = totals.map(t -> new LineInput(t.absentDays(), t.halfDays(), t.lateDays(), t.leaveDays(), 0,
					null, null, null, null)).orElse(new LineInput(0, 0, 0, 0, 0, null, null, null, null));
			Calc c = calculate(basic, (BigDecimal) s.get("rate"), days, in);
			jdbc.update("""
					insert into salary_sheet_line (school_id, sheet_id, staff_id, basic, per_lecture_rate, lectures,
					                               working_days, absent_days, half_days, late_days, leave_days,
					                               deduction_days, attendance_deduction, allowance, tax, other_deduction,
					                               earned, net, breakdown, attendance_source)
					values (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?::jsonb, ?)""",
					TenantContext.requireSchoolId(), sheetId, staffId, basic, s.get("rate"), days, in.absentDays(),
					in.halfDays(), in.lateDays(), in.leaveDays(), c.deductionDays(), c.deduction(), c.earned(), c.net(),
					json.writeValueAsString(c.breakdown()), totals.isPresent() ? "ATTENDANCE" : "MANUAL");
		}
		audit.record("SALARY_SHEET_CREATED", "salary_sheet", sheetId, null,
				Map.of("month", month.toString(), "workingDays", days, "skipped", skipped));
		SheetView view = get(sheetId);
		return withSkipped(view, skipped);
	}

	record Calc(BigDecimal deductionDays, BigDecimal deduction, BigDecimal earned, BigDecimal net,
			Map<String, Object> breakdown) {
	}

	Calc calculate(BigDecimal basic, BigDecimal rate, int days, LineInput in) {
		boolean deductAbsent = settings.bool(SettingKey.DEDUCT_ABSENT_DAYS);
		boolean deductHalf = settings.bool(SettingKey.HALF_DAY_DEDUCTION);
		int latePer = settings.integer(SettingKey.LATE_DAYS_PER_ABSENCE);
		int paidLeave = Math.max(0, settings.integer(SettingKey.PAID_LEAVE_DAYS_PER_MONTH));
		BigDecimal absent = deductAbsent ? BigDecimal.valueOf(in.absentDays()) : BigDecimal.ZERO;
		BigDecimal half = deductHalf ? BigDecimal.valueOf(in.halfDays()).multiply(new BigDecimal("0.5")) : BigDecimal.ZERO;
		BigDecimal lateAsAbsent = latePer > 0 ? BigDecimal.valueOf(in.lateDays() / latePer) : BigDecimal.ZERO;
		BigDecimal unpaidLeave = BigDecimal.valueOf(Math.max(0, in.leaveDays() - paidLeave));
		BigDecimal deductionDays = absent.add(half).add(lateAsAbsent).add(unpaidLeave);
		BigDecimal deduction = Money.of(basic.multiply(deductionDays).divide(BigDecimal.valueOf(days), 10,
				RoundingMode.HALF_UP)).amount().min(basic);
		BigDecimal allowance = MoneyInput.parseOrZero(in.allowance(), "Allowance");
		BigDecimal tax = MoneyInput.parseOrZero(in.tax(), "Tax");
		BigDecimal other = MoneyInput.parseOrZero(in.otherDeduction(), "Other deduction");
		BigDecimal lecturePay = rate == null ? BigDecimal.ZERO
				: Money.of(rate.multiply(BigDecimal.valueOf(in.lectures()))).amount();
		BigDecimal earned = basic.subtract(deduction).add(allowance).add(lecturePay);
		BigDecimal net = earned.subtract(tax).subtract(other);
		if (net.signum() < 0) {
			throw ApiException.businessRule("NET_BELOW_ZERO", "Tax and deductions are more than the salary earned");
		}
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("perDay", Money.of(basic.divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP)).toString());
		b.put("absentDays", absent.toPlainString());
		b.put("halfDaysCounted", half.toPlainString());
		b.put("lateDaysAsAbsence", lateAsAbsent.toPlainString());
		b.put("lateDaysPerAbsence", latePer);
		b.put("unpaidLeaveDays", unpaidLeave.toPlainString());
		b.put("paidLeaveAllowed", paidLeave);
		b.put("lecturePay", Money.of(lecturePay).toString());
		b.put("formula", "deduction = basic × deduction days ÷ " + days + " working days");
		return new Calc(deductionDays, deduction, Money.of(earned).amount(), Money.of(net).amount(), b);
	}

	// ------------------------------------------------------------------- edit

	@Transactional
	public SheetView updateLine(long lineId, LineInput in) {
		if (in.absentDays() < 0 || in.halfDays() < 0 || in.lateDays() < 0 || in.leaveDays() < 0 || in.lectures() < 0) {
			throw ApiException.businessRule("DAYS_INVALID", "Days cannot be negative");
		}
		Map<String, Object> line = jdbc.queryForList("""
				select l.sheet_id, l.basic, l.per_lecture_rate, l.working_days, s.status from salary_sheet_line l
				join salary_sheet s on s.id = l.sheet_id where l.id = ? for update of s""", lineId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Salary line"));
		requireDraft((String) line.get("status"));
		int days = ((Number) line.get("working_days")).intValue();
		if (in.absentDays() + in.leaveDays() > days || in.halfDays() > days || in.lateDays() > days) {
			throw ApiException.businessRule("DAYS_INVALID", "More days than the " + days + " working days in the month");
		}
		Calc c = calculate((BigDecimal) line.get("basic"), (BigDecimal) line.get("per_lecture_rate"), days, in);
		jdbc.update("""
				update salary_sheet_line set absent_days = ?, half_days = ?, late_days = ?, leave_days = ?, lectures = ?,
				       allowance = ?, tax = ?, other_deduction = ?, other_note = ?, deduction_days = ?,
				       attendance_deduction = ?, earned = ?, net = ?, breakdown = ?::jsonb, attendance_source = 'MANUAL'
				where id = ?""", in.absentDays(), in.halfDays(), in.lateDays(), in.leaveDays(), in.lectures(),
				MoneyInput.parseOrZero(in.allowance(), "Allowance"), MoneyInput.parseOrZero(in.tax(), "Tax"),
				MoneyInput.parseOrZero(in.otherDeduction(), "Other deduction"), PakFormats.trimToNull(in.otherNote()),
				c.deductionDays(), c.deduction(), c.earned(), c.net(), json.writeValueAsString(c.breakdown()), lineId);
		long sheetId = ((Number) line.get("sheet_id")).longValue();
		jdbc.update("update salary_sheet set version = version + 1 where id = ?", sheetId);
		return get(sheetId);
	}

	// --------------------------------------------------------------- workflow

	@Transactional
	public SheetView approve(long sheetId) {
		Map<String, Object> sheet = lockSheet(sheetId);
		requireDraft((String) sheet.get("status"));
		SheetView view = get(sheetId);
		if (view.lines().isEmpty()) {
			throw ApiException.businessRule("EMPTY_SHEET", "The sheet has nobody on it");
		}
		YearMonth month = YearMonth.parse(view.month());
		LocalDate date = month.atEndOfMonth().isAfter(MoneyInput.today(clock)) ? MoneyInput.today(clock)
				: month.atEndOfMonth();
		BigDecimal earned = new BigDecimal(view.totalEarned());
		long entry = journal.post(date, "SALARY", "SALARY_SHEET", sheetId, "Salaries for " + view.month(), null,
				List.of(Line.debit(journal.accountOf("SALARY_EXPENSE"), earned),
						Line.credit(journal.accountOf("SALARY_PAYABLE"), new BigDecimal(view.totalNet())),
						Line.credit(journal.accountOf("TAX_PAYABLE"), new BigDecimal(view.totalTax())),
						Line.credit(journal.accountOf("SALARY_DEDUCTION"), new BigDecimal(view.totalOther()))));
		jdbc.update("""
				update salary_sheet set status = 'APPROVED', approved_by = ?, approved_at = ?, approve_journal_id = ?,
				       version = version + 1 where id = ?""", CurrentActor.require().displayName(),
				Timestamp.from(clock.instant()), entry, sheetId);
		audit.record("SALARY_SHEET_APPROVED", "salary_sheet", sheetId, null,
				Map.of("month", view.month(), "earned", view.totalEarned(), "net", view.totalNet(), "journalEntryId", entry));
		return get(sheetId);
	}

	/** Pays an approved sheet from one account. The same Idempotency-Key returns the sheet unchanged. */
	@Transactional
	public SheetView pay(long sheetId, long accountId, LocalDate paymentDate, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		Map<String, Object> sheet = lockSheet(sheetId);
		String status = (String) sheet.get("status");
		if ("PAID".equals(status)) {
			if (idempotencyKey.trim().equals(sheet.get("pay_idempotency_key"))) {
				return get(sheetId);
			}
			throw ApiException.conflict("ALREADY_PAID", "These salaries are already paid");
		}
		if (!"APPROVED".equals(status)) {
			throw ApiException.businessRule("NOT_APPROVED", "Approve the sheet before paying it");
		}
		Map<String, Object> account = jdbc.queryForList(
				"select name, subtype, status from ledger_account where id = ?", accountId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Account"));
		if (!List.of("CASH", "BANK").contains(account.get("subtype")) || !"ACTIVE".equals(account.get("status"))) {
			throw ApiException.businessRule("ACCOUNT_INVALID", "Choose an active cash or bank account");
		}
		LocalDate today = MoneyInput.today(clock);
		LocalDate date = paymentDate == null ? today : paymentDate;
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "The payment date cannot be in the future");
		}
		SheetView view = get(sheetId);
		BigDecimal net = new BigDecimal(view.totalNet());
		long entry = journal.post(date, "SALARY", "SALARY_PAYMENT", sheetId, "Salaries paid for " + view.month(), null,
				List.of(Line.debit(journal.accountOf("SALARY_PAYABLE"), net), Line.credit(accountId, net)));
		jdbc.update("""
				update salary_sheet set status = 'PAID', paid_by = ?, paid_at = ?, payment_account_id = ?, payment_date = ?,
				       pay_idempotency_key = ?, pay_journal_id = ?, version = version + 1 where id = ?""",
				CurrentActor.require().displayName(), Timestamp.from(clock.instant()), accountId, Date.valueOf(date),
				idempotencyKey.trim(), entry, sheetId);
		audit.record("SALARY_SHEET_PAID", "salary_sheet", sheetId, null,
				Map.of("month", view.month(), "net", view.totalNet(), "account", account.get("name"), "journalEntryId",
						entry));
		return get(sheetId);
	}

	/** Reverses an approved or paid sheet (or discards a draft); a new sheet can then be made for the month. */
	@Transactional
	public SheetView reverse(long sheetId, String reason) {
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason");
		}
		Map<String, Object> sheet = lockSheet(sheetId);
		String status = (String) sheet.get("status");
		if ("REVERSED".equals(status)) {
			throw ApiException.conflict("ALREADY_REVERSED", "This sheet is already reversed");
		}
		LocalDate today = MoneyInput.today(clock);
		if (sheet.get("pay_journal_id") != null) {
			journal.reverse(((Number) sheet.get("pay_journal_id")).longValue(), today, "SALARY_SHEET_REVERSAL", sheetId,
					"Reversal of salary payment: " + why);
		}
		if (sheet.get("approve_journal_id") != null) {
			journal.reverse(((Number) sheet.get("approve_journal_id")).longValue(), today, "SALARY_SHEET_REVERSAL",
					sheetId, "Reversal of salaries: " + why);
		}
		jdbc.update("""
				update salary_sheet set status = 'REVERSED', reversed_by = ?, reversed_at = ?, reversal_reason = ?,
				       version = version + 1 where id = ?""", CurrentActor.require().displayName(),
				Timestamp.from(clock.instant()), why, sheetId);
		audit.record("DRAFT".equals(status) ? "SALARY_SHEET_DISCARDED" : "SALARY_SHEET_REVERSED", "salary_sheet",
				sheetId, Map.of("status", status), Map.of("status", "REVERSED"), why);
		return get(sheetId);
	}

	// ------------------------------------------------------------------ reads

	@Transactional(readOnly = true)
	public List<SheetRow> sheets() {
		return jdbc.query("""
				select s.id, s.month, s.status, s.working_days, count(l.id), coalesce(sum(l.net), 0), s.created_by,
				       s.created_at
				from salary_sheet s left join salary_sheet_line l on l.sheet_id = s.id
				group by s.id order by s.month desc, s.id desc limit 120""",
				(rs, i) -> new SheetRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
						MoneyInput.text(rs.getBigDecimal(6)), rs.getString(7), rs.getTimestamp(8).toInstant()));
	}

	@Transactional(readOnly = true)
	public SheetView get(long sheetId) {
		List<LineView> lines = jdbc.query("""
				select l.id, l.staff_id, st.employee_no, st.name, st.designation, l.basic, l.per_lecture_rate, l.lectures,
				       l.working_days, l.absent_days, l.half_days, l.late_days, l.leave_days, l.deduction_days,
				       l.attendance_deduction, l.allowance, l.tax, l.other_deduction, l.other_note, l.earned, l.net,
				       l.attendance_source, l.breakdown::text
				from salary_sheet_line l join staff st on st.id = l.staff_id
				where l.sheet_id = ? order by st.employee_no""", (rs, i) -> new LineView(rs.getLong(1), rs.getLong(2),
				rs.getString(3), rs.getString(4), rs.getString(5), MoneyInput.text(rs.getBigDecimal(6)),
				MoneyInput.text(rs.getBigDecimal(7)), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
				rs.getInt(12), rs.getInt(13), rs.getBigDecimal(14).stripTrailingZeros().toPlainString(),
				MoneyInput.text(rs.getBigDecimal(15)), MoneyInput.text(rs.getBigDecimal(16)),
				MoneyInput.text(rs.getBigDecimal(17)), MoneyInput.text(rs.getBigDecimal(18)), rs.getString(19),
				MoneyInput.text(rs.getBigDecimal(20)), MoneyInput.text(rs.getBigDecimal(21)), rs.getString(22),
				readMap(rs.getString(23))), sheetId);
		List<SheetView> rows = jdbc.query("""
				select s.id, s.month, s.status, s.working_days, s.created_by, s.created_at, s.approved_by, s.approved_at,
				       s.paid_by, s.paid_at, s.payment_account_id, a.name, s.payment_date, s.reversed_by, s.reversed_at,
				       s.reversal_reason, s.version
				from salary_sheet s left join ledger_account a on a.id = s.payment_account_id where s.id = ?""",
				(rs, i) -> new SheetView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
						rs.getTimestamp(6).toInstant(), rs.getString(7), instant(rs.getTimestamp(8)), rs.getString(9),
						instant(rs.getTimestamp(10)), (Long) rs.getObject(11), rs.getString(12),
						rs.getDate(13) == null ? null : rs.getDate(13).toLocalDate(), rs.getString(14),
						instant(rs.getTimestamp(15)), rs.getString(16), total(lines, LineView::earned),
						total(lines, LineView::tax), total(lines, LineView::otherDeduction), total(lines, LineView::net),
						lines, List.of(), rs.getLong(17)),
				sheetId);
		if (rows.isEmpty()) {
			throw ApiException.notFound("Salary sheet");
		}
		return rows.getFirst();
	}

	public LineView line(long lineId) {
		Long sheetId = jdbc.queryForList("select sheet_id from salary_sheet_line where id = ?", Long.class, lineId).stream()
			.findFirst().orElseThrow(() -> ApiException.notFound("Salary line"));
		return get(sheetId).lines().stream().filter(l -> l.id() == lineId).findFirst().orElseThrow();
	}

	// ---------------------------------------------------------------- helpers

	private Map<String, Object> lockSheet(long sheetId) {
		return jdbc.queryForList("""
				select status, approve_journal_id, pay_journal_id, pay_idempotency_key from salary_sheet
				where id = ? for update""", sheetId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Salary sheet"));
	}

	private static void requireDraft(String status) {
		if (!"DRAFT".equals(status)) {
			throw ApiException.conflict("SHEET_LOCKED",
					"The sheet is " + status.toLowerCase() + "; reverse it to make corrections");
		}
	}

	private static YearMonth month(String raw) {
		try {
			return YearMonth.parse(raw == null ? "" : raw.trim());
		}
		catch (RuntimeException e) {
			throw ApiException.businessRule("MONTH_INVALID", "The month must look like 2026-10");
		}
	}

	private static String total(List<LineView> lines, java.util.function.Function<LineView, String> field) {
		return MoneyInput.text(lines.stream().map(l -> new BigDecimal(field.apply(l))).reduce(BigDecimal.ZERO,
				BigDecimal::add));
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String text) {
		return text == null ? Map.of() : json.readValue(text, Map.class);
	}

	private static SheetView withSkipped(SheetView v, List<String> skipped) {
		return new SheetView(v.id(), v.month(), v.status(), v.workingDays(), v.createdBy(), v.createdAt(),
				v.approvedBy(), v.approvedAt(), v.paidBy(), v.paidAt(), v.paymentAccountId(), v.paymentAccountName(),
				v.paymentDate(), v.reversedBy(), v.reversedAt(), v.reversalReason(), v.totalEarned(), v.totalTax(),
				v.totalOther(), v.totalNet(), v.lines(), skipped, v.version());
	}
}
