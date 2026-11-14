package com.selectcode.sms.fees.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.Money;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bill generation (FR 10.5) and late fees (FR 10.11).
 *
 * <p>A run is identified by its Idempotency-Key: repeating the request returns the first run's report. Independently,
 * the database never lets a student be charged the same head for the same period twice, so any run (repeated, or
 * restarted after a crash) only creates what is still missing (QA-FEE-005/006/007). Students are processed in chunks,
 * each chunk in its own transaction, so a large school does not hold one huge transaction.
 */
@Service
public class BillingService {

	static final int CHUNK = 50;

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

	public record BillRunInput(String title, LocalDate issueDate, LocalDate dueDate, String period, String termLabel,
			List<Long> headIds, List<Long> classIds, List<Long> sectionIds, List<Long> studentIds,
			List<Long> familyIds, List<Long> excludeStudentIds, Boolean applyCredit) {
	}

	public record Missing(long studentId, String studentName, String grNo, String className, String headName) {
	}

	public record RunReport(long runId, String kind, String status, int students, int billsCreated, int linesCreated,
			int alreadyBilled, String totalBilled, String creditApplied, List<Missing> missing, boolean replayed) {
	}

	public record RunView(long id, String kind, String status, String title, String createdBy, Instant startedAt,
			Instant finishedAt, RunReport report) {
	}

	public record PlannedLine(long feeHeadId, String headName, String periodKey, String description, String gross,
			String discount, String net, String discountNote) {
	}

	public record LateFeeInput(LocalDate dueOnOrBefore, String amount, List<Long> excludeClassIds) {
	}

	record Head(long id, String name, String frequency, int order) {
	}

	record StudentRow(long id, String name, String grNo, long familyId, long classId, long sectionId,
			String className, int childRank) {
	}

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final YearContext yearContext;

	private final SettingsService settings;

	private final NumberSequences sequences;

	private final FeeLedger ledger;

	private final CreditApplier credit;

	private final AuditService audit;

	private final JsonMapper json;

	private final Clock clock;

	private final org.springframework.beans.factory.ObjectProvider<com.selectcode.sms.shared.attendance.StudentAbsences> absences;

	BillingService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, YearContext yearContext,
			SettingsService settings, NumberSequences sequences, FeeLedger ledger, CreditApplier credit,
			AuditService audit, JsonMapper json, Clock clock,
			org.springframework.beans.factory.ObjectProvider<com.selectcode.sms.shared.attendance.StudentAbsences> absences) {
		this.absences = absences;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.yearContext = yearContext;
		this.settings = settings;
		this.sequences = sequences;
		this.ledger = ledger;
		this.credit = credit;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	// ------------------------------------------------------------- bill runs

	/** Not transactional itself: the run record and each chunk of students commit separately. */
	public RunReport run(BillRunInput in, String idempotencyKey) {
		String key = requireKey(idempotencyKey);
		YearRef year = yearContext.requireWritable();
		LocalDate today = Amounts.today(clock);
		LocalDate issue = in.issueDate() == null ? today : in.issueDate();
		if (issue.isBefore(year.startDate()) || issue.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The issue date must be within " + year.name());
		}
		LocalDate due = in.dueDate() != null ? in.dueDate() : defaultDueDate(issue);
		if (due.isBefore(issue)) {
			throw ApiException.businessRule("DATES_INVALID", "The due date is before the issue date");
		}
		LocalDate validity = due.plusDays(settings.integer(SettingKey.VOUCHER_VALIDITY_DAYS));
		List<Head> heads = heads(in.headIds());
		String period = null;
		if (heads.stream().anyMatch(h -> "MONTHLY".equals(h.frequency()))) {
			period = month(in.period(), issue);
		}
		String term = PakFormats.trimToNull(in.termLabel());
		if (heads.stream().anyMatch(h -> Set.of("PER_TERM", "CUSTOM").contains(h.frequency())) && term == null) {
			throw ApiException.businessRule("TERM_REQUIRED", "Enter the term or period label, e.g. Term 1");
		}
		if (term != null && term.length() > 30) {
			throw ApiException.businessRule("TERM_INVALID", "The term label can be at most 30 characters");
		}
		String title = PakFormats.trimToNull(in.title()) != null ? in.title().trim()
				: period != null ? "Fee bill " + YearMonth.parse(period).format(MONTH) : "Fee bill " + year.name();
		boolean applyCredit = in.applyCredit() == null || in.applyCredit();
		String monthKey = period;
		// 1. The run record (or the earlier run with this key).
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("title", title);
		params.put("issueDate", issue.toString());
		params.put("dueDate", due.toString());
		params.put("period", period);
		params.put("termLabel", term);
		params.put("headIds", in.headIds());
		params.put("classIds", in.classIds());
		params.put("sectionIds", in.sectionIds());
		params.put("studentIds", in.studentIds());
		params.put("familyIds", in.familyIds());
		params.put("excludeStudentIds", in.excludeStudentIds());
		Object[] start = transaction.execute(tx -> {
			ledger.lockKey("fee_run", key);
			var existing = jdbc.query("select id, status, report::text from fee_run where idempotency_key = ?",
					(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3) }, key);
			if (!existing.isEmpty()) {
				return existing.getFirst();
			}
			Long id = jdbc.queryForObject("""
					insert into fee_run (school_id, kind, academic_year_id, idempotency_key, parameters, status,
					                     created_by)
					values (?, 'BILLS', ?, ?, ?::jsonb, 'RUNNING', ?) returning id""", Long.class,
					TenantContext.requireSchoolId(), year.id(), key, json.writeValueAsString(params),
					CurrentActor.require().displayName());
			return new Object[] { id, "NEW", null };
		});
		long runId = (Long) start[0];
		if ("DONE".equals(start[1])) {
			RunReport done = json.readValue((String) start[2], RunReport.class);
			return new RunReport(done.runId(), done.kind(), done.status(), done.students(), done.billsCreated(),
					done.linesCreated(), done.alreadyBilled(), done.totalBilled(), done.creditApplied(), done.missing(),
					true);
		}
		// 2. Students in scope, in chunks.
		List<StudentRow> students = students(year, issue, in);
		int bills = 0;
		int lines = 0;
		int already = 0;
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal creditUsed = BigDecimal.ZERO;
		List<Missing> missing = new ArrayList<>();
		for (int from = 0; from < students.size(); from += CHUNK) {
			List<StudentRow> chunk = students.subList(from, Math.min(from + CHUNK, students.size()));
			ChunkResult r = transaction.execute(tx -> {
				ChunkResult result = new ChunkResult();
				Set<Long> families = new HashSet<>();
				for (StudentRow s : chunk) {
					ledger.lockFamily(s.familyId());
					Plan plan = plan(s, heads, year, issue, monthKey, term);
					result.missing.addAll(plan.missing);
					List<PlannedLine> fresh = new ArrayList<>();
					for (PlannedLine line : plan.lines) {
						if (alreadyBilled(s.id(), line.feeHeadId(), line.periodKey())) {
							result.already++;
						}
						else {
							fresh.add(line);
						}
					}
					if (fresh.isEmpty()) {
						continue;
					}
					createBill(s, year, title, issue, due, validity, runId, fresh);
					result.bills++;
					result.lines += fresh.size();
					for (PlannedLine line : fresh) {
						result.total = result.total.add(new BigDecimal(line.net()));
					}
					families.add(s.familyId());
				}
				if (applyCredit) {
					for (long familyId : families) {
						result.credit = result.credit.add(credit.apply(familyId, issue));
					}
				}
				return result;
			});
			bills += r.bills;
			lines += r.lines;
			already += r.already;
			total = total.add(r.total);
			creditUsed = creditUsed.add(r.credit);
			missing.addAll(r.missing);
		}
		RunReport report = new RunReport(runId, "BILLS", "DONE", students.size(), bills, lines, already,
				Amounts.text(total), Amounts.text(creditUsed), missing, false);
		transaction.executeWithoutResult(tx -> {
			jdbc.update("update fee_run set status = 'DONE', report = ?::jsonb, finished_at = now() where id = ?",
					json.writeValueAsString(report), runId);
			audit.record("BILLS_GENERATED", "fee_run", runId, null, Map.of("title", title, "bills", report.billsCreated(),
					"lines", report.linesCreated(), "alreadyBilled", report.alreadyBilled(), "total", report.totalBilled()));
		});
		return report;
	}

	static final class ChunkResult {

		int bills;

		int lines;

		int already;

		BigDecimal total = BigDecimal.ZERO;

		BigDecimal credit = BigDecimal.ZERO;

		List<Missing> missing = new ArrayList<>();
	}

	record Plan(List<PlannedLine> lines, List<Missing> missing) {
	}

	/** Charges a student would get for the heads (amount, discounts, sibling rule), without saving anything. */
	Plan plan(StudentRow s, List<Head> heads, YearRef year, LocalDate issue, String period, String term) {
		List<PlannedLine> lines = new ArrayList<>();
		List<Missing> missing = new ArrayList<>();
		List<Object[]> siblingRules = jdbc.query("select child_order, percent, fee_head_ids from sibling_discount_rule "
				+ "where child_order <= ? order by child_order desc limit 1",
				(rs, i) -> new Object[] { rs.getInt(1), rs.getBigDecimal(2), FeeSetupService.longs(rs.getArray(3)) },
				s.childRank());
		for (Head h : heads) {
			BigDecimal gross = amount(s, h, year);
			if (gross == null) {
				missing.add(new Missing(s.id(), s.name(), s.grNo(), s.className(), h.name()));
				continue;
			}
			if (gross.signum() == 0) {
				continue;
			}
			String periodKey = switch (h.frequency()) {
				case "MONTHLY" -> period;
				case "YEARLY" -> year.name();
				case "ONE_TIME" -> "ONCE";
				default -> term;
			};
			String description = switch (h.frequency()) {
				case "MONTHLY" -> h.name() + " - " + YearMonth.parse(period).format(MONTH);
				case "YEARLY" -> h.name() + " - " + year.name();
				case "ONE_TIME" -> h.name();
				default -> h.name() + " - " + term;
			};
			BigDecimal percent = BigDecimal.ZERO;
			BigDecimal fixed = BigDecimal.ZERO;
			List<String> notes = new ArrayList<>();
			for (Object[] d : jdbc.query("""
					select kind, value, reason from discount
					where student_id = ? and status in ('APPROVED', 'ENDED') and (fee_head_id is null or fee_head_id = ?)
					  and valid_from <= ? and (valid_to is null or valid_to >= ?) order by id""",
					(rs, i) -> new Object[] { rs.getString(1), rs.getBigDecimal(2), rs.getString(3) }, s.id(), h.id(),
					Date.valueOf(issue), Date.valueOf(issue))) {
				if ("PERCENT".equals(d[0])) {
					percent = percent.add((BigDecimal) d[1]);
					notes.add(d[2] + " " + ((BigDecimal) d[1]).stripTrailingZeros().toPlainString() + "%");
				}
				else {
					fixed = fixed.add((BigDecimal) d[1]);
					notes.add(d[2] + " " + Amounts.rupees((BigDecimal) d[1]));
				}
			}
			if (!siblingRules.isEmpty()) {
				Object[] rule = siblingRules.getFirst();
				@SuppressWarnings("unchecked")
				List<Long> ruleHeads = (List<Long>) rule[2];
				if (ruleHeads.isEmpty() || ruleHeads.contains(h.id())) {
					percent = percent.add((BigDecimal) rule[1]);
					notes.add("Sibling (child " + s.childRank() + ") "
							+ ((BigDecimal) rule[1]).stripTrailingZeros().toPlainString() + "%");
				}
			}
			// One rounding step (HALF_UP, 2 dp) so voucher, ledger and reports agree (QA-FEE-028).
			BigDecimal discount = Money.of(gross.multiply(percent).divide(BigDecimal.valueOf(100))).plus(Money.of(fixed))
				.amount()
				.min(gross);
			BigDecimal net = gross.subtract(discount);
			lines.add(new PlannedLine(h.id(), h.name(), periodKey, description, Amounts.text(gross),
					Amounts.text(discount), Amounts.text(net), notes.isEmpty() ? null : String.join("; ", notes)));
		}
		return new Plan(lines, missing);
	}

	private BigDecimal amount(StudentRow s, Head h, YearRef year) {
		List<BigDecimal> own = jdbc.queryForList("""
				select amount from student_fee_override where student_id = ? and academic_year_id = ? and fee_head_id = ?""",
				BigDecimal.class, s.id(), year.id(), h.id());
		if (!own.isEmpty()) {
			return own.getFirst();
		}
		List<BigDecimal> cls = jdbc.queryForList("""
				select amount from fee_structure where academic_year_id = ? and class_id = ? and fee_head_id = ?""",
				BigDecimal.class, year.id(), s.classId(), h.id());
		return cls.isEmpty() ? null : cls.getFirst();
	}

	private boolean alreadyBilled(long studentId, long headId, String periodKey) {
		Long n = jdbc.queryForObject(
				"select count(*) from bill_line where student_id = ? and fee_head_id = ? and period_key = ?", Long.class,
				studentId, headId, periodKey);
		return n != null && n > 0;
	}

	private void createBill(StudentRow s, YearRef year, String title, LocalDate issue, LocalDate due,
			LocalDate validity, Long runId, List<PlannedLine> lines) {
		long schoolId = TenantContext.requireSchoolId();
		String billNo = NumberSequences.format("B", sequences.next("bill"), 6);
		Long billId = jdbc.queryForObject("""
				insert into bill (school_id, bill_no, student_id, family_id, academic_year_id, class_id, section_id,
				                  title, issue_date, due_date, validity_date, fee_run_id, created_by)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, schoolId, billNo, s.id(),
				s.familyId(), year.id(), s.classId(), s.sectionId(), title, Date.valueOf(issue), Date.valueOf(due),
				Date.valueOf(validity), runId, CurrentActor.require().displayName());
		for (PlannedLine line : lines) {
			jdbc.update("""
					insert into bill_line (school_id, bill_id, student_id, fee_head_id, period_key, description, gross,
					                       discount, net, discount_note)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, billId, s.id(), line.feeHeadId(),
					line.periodKey(), line.description(), new BigDecimal(line.gross()), new BigDecimal(line.discount()),
					new BigDecimal(line.net()), line.discountNote());
		}
	}

	private List<StudentRow> students(YearRef year, LocalDate onDate, BillRunInput in) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				select s.id, s.name, s.gr_no, s.family_id, e.class_id, e.section_id, c.name,
				       (select count(*) from student o where o.family_id = s.family_id and o.status = 'ACTIVE'
				          and (o.admission_date < s.admission_date or (o.admission_date = s.admission_date and o.id <= s.id)))
				from student s
				join enrolment e on e.student_id = s.id and e.academic_year_id = ?
				     and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				join school_class c on c.id = e.class_id
				join section sec on sec.id = e.section_id
				where s.status = 'ACTIVE'""");
		args.add(year.id());
		args.add(Date.valueOf(onDate));
		args.add(Date.valueOf(onDate));
		in(sql, args, "e.class_id", in.classIds());
		in(sql, args, "e.section_id", in.sectionIds());
		in(sql, args, "s.id", in.studentIds());
		in(sql, args, "s.family_id", in.familyIds());
		if (in.excludeStudentIds() != null && !in.excludeStudentIds().isEmpty()) {
			sql.append(" and s.id not in (").append(placeholders(in.excludeStudentIds().size())).append(")");
			args.addAll(in.excludeStudentIds());
		}
		sql.append(" order by c.display_order, sec.name, s.name, s.id");
		return jdbc.query(sql.toString(), (rs, i) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getInt(8)), args.toArray());
	}

	private List<Head> heads(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw ApiException.businessRule("HEADS_REQUIRED", "Choose the fees to charge");
		}
		List<Head> heads = jdbc.query("select id, name, frequency, display_order from fee_head where id in ("
				+ placeholders(ids.size()) + ") and status = 'ACTIVE' and system_code is null order by display_order",
				(rs, i) -> new Head(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)), ids.toArray());
		if (heads.size() != new HashSet<>(ids).size()) {
			throw ApiException.businessRule("HEAD_INVALID", "One of the chosen fees is not active");
		}
		return heads;
	}

	private LocalDate defaultDueDate(LocalDate issue) {
		int day = Math.max(1, Math.min(28, settings.integer(SettingKey.FEE_DUE_DAY)));
		LocalDate due = issue.withDayOfMonth(day);
		return due.isBefore(issue) ? due.plusMonths(1) : due;
	}

	private static String month(String raw, LocalDate issue) {
		if (raw == null || raw.isBlank()) {
			return YearMonth.from(issue).toString();
		}
		try {
			return YearMonth.parse(raw.trim()).toString();
		}
		catch (RuntimeException e) {
			throw ApiException.businessRule("PERIOD_INVALID", "The month must look like 2026-10");
		}
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank() || key.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		return key.trim();
	}

	private static void in(StringBuilder sql, List<Object> args, String column, List<Long> ids) {
		if (ids != null && !ids.isEmpty()) {
			sql.append(" and ").append(column).append(" in (").append(placeholders(ids.size())).append(")");
			args.addAll(ids);
		}
	}

	static String placeholders(int n) {
		return String.join(", ", java.util.Collections.nCopies(n, "?"));
	}

	// ------------------------------------------------------------ admission

	/**
	 * Admission charges proposed after enrolment (FR 4.10): one-time heads plus the monthly heads for the admission
	 * month, at the student's class amounts. Nothing is saved; the bill is made with a normal run.
	 */
	@Transactional(readOnly = true)
	public List<PlannedLine> admissionProposal(long studentId) {
		YearRef year = yearContext.requireSelected();
		List<StudentRow> rows = jdbc.query("""
				select s.id, s.name, s.gr_no, s.family_id, e.class_id, e.section_id, c.name,
				       (select count(*) from student o where o.family_id = s.family_id and o.status = 'ACTIVE'
				          and (o.admission_date < s.admission_date or (o.admission_date = s.admission_date and o.id <= s.id))),
				       s.admission_date
				from student s join enrolment e on e.student_id = s.id and e.academic_year_id = ?
				join school_class c on c.id = e.class_id
				where s.id = ? order by e.effective_from limit 1""",
				(rs, i) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
						rs.getLong(6), rs.getString(7), rs.getInt(8)),
				year.id(), studentId);
		if (rows.isEmpty()) {
			return List.of();
		}
		LocalDate admitted = jdbc.queryForObject("select admission_date from student where id = ?", Date.class, studentId)
			.toLocalDate();
		LocalDate on = admitted.isBefore(year.startDate()) ? year.startDate() : admitted;
		List<Head> heads = jdbc.query("""
				select id, name, frequency, display_order from fee_head
				where status = 'ACTIVE' and system_code is null and frequency in ('ONE_TIME', 'MONTHLY')
				order by display_order""", (rs, i) -> new Head(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getInt(4)));
		Plan plan = plan(rows.getFirst(), heads, year, on, YearMonth.from(on).toString(), null);
		return plan.lines().stream().filter(l -> !alreadyBilled(studentId, l.feeHeadId(), l.periodKey())).toList();
	}

	// ------------------------------------------------------------- late fee

	/** Adds the late fee to every bill due on or before the date that is still unpaid (once per bill, FR 10.11). */
	/**
	 * Absent fine (FR 10.12): the school's fine per day × unexplained absences (status A on working days; leave and
	 * holidays never count) in the month, as one bill per student. Once per student and month.
	 */
	public RunReport absentFine(String monthRaw, String idempotencyKey) {
		String key = requireKey(idempotencyKey);
		YearRef year = yearContext.requireWritable();
		YearMonth month = YearMonth.parse(month(monthRaw, Amounts.today(clock)));
		if (month.isAfter(YearMonth.from(Amounts.today(clock)))) {
			throw ApiException.businessRule("MONTH_IN_FUTURE", "Choose this month or an earlier one");
		}
		BigDecimal perDay = settings.money(SettingKey.ABSENT_FINE_AMOUNT).amount();
		if (perDay.signum() <= 0) {
			throw ApiException.businessRule("FINE_OFF", "Set the absent fine per day in Rules & settings first");
		}
		var source = absences.getIfAvailable();
		if (source == null) {
			throw new IllegalStateException("Attendance is not available");
		}
		Map<Long, Integer> counts = source.absencesInMonth(month);
		LocalDate issue = Amounts.today(clock);
		LocalDate due = defaultDueDate(issue);
		LocalDate validity = due.plusDays(settings.integer(SettingKey.VOUCHER_VALIDITY_DAYS));
		return transaction.execute(tx -> {
			ledger.lockKey("fee_run", key);
			var existing = jdbc.query("select report::text from fee_run where idempotency_key = ? and status = 'DONE'",
					(rs, i) -> rs.getString(1), key);
			if (!existing.isEmpty()) {
				RunReport done = json.readValue(existing.getFirst(), RunReport.class);
				return new RunReport(done.runId(), done.kind(), done.status(), done.students(), done.billsCreated(),
						done.linesCreated(), done.alreadyBilled(), done.totalBilled(), done.creditApplied(), List.of(), true);
			}
			Long headId = jdbc.queryForList("select id from fee_head where system_code = 'ABSENT_FINE' and status = 'ACTIVE'",
					Long.class).stream().findFirst()
				.orElseThrow(() -> ApiException.businessRule("FINE_OFF", "The Absent fine head is switched off"));
			Long runId = jdbc.queryForObject("""
					insert into fee_run (school_id, kind, academic_year_id, idempotency_key, parameters, status, created_by)
					values (?, 'ABSENT_FINE', ?, ?, ?::jsonb, 'RUNNING', ?) returning id""", Long.class,
					TenantContext.requireSchoolId(), year.id(), key,
					json.writeValueAsString(Map.of("month", month.toString(), "perDay", perDay.toPlainString())),
					CurrentActor.require().displayName());
			String period = "ABSENT-" + month;
			int bills = 0;
			int already = 0;
			BigDecimal total = BigDecimal.ZERO;
			for (var c : counts.entrySet()) {
				List<StudentRow> rows = jdbc.query("""
						select s.id, s.name, s.gr_no, s.family_id, e.class_id, e.section_id, c.name, 1
						from student s join enrolment e on e.student_id = s.id and e.academic_year_id = ?
						join school_class c on c.id = e.class_id
						where s.id = ? and s.status = 'ACTIVE' order by e.effective_from desc limit 1""",
						(rs, i) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
								rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getInt(8)), year.id(), c.getKey());
				if (rows.isEmpty()) {
					continue;
				}
				StudentRow s = rows.getFirst();
				ledger.lockFamily(s.familyId());
				if (alreadyBilled(s.id(), headId, period)) {
					already++;
					continue;
				}
				BigDecimal amount = perDay.multiply(BigDecimal.valueOf(c.getValue()));
				String text = "Absent fine - " + month.format(MONTH) + " (" + c.getValue() + " day"
						+ (c.getValue() == 1 ? "" : "s") + ")";
				createBill(s, year, "Absent fine " + month.format(MONTH), issue, due, validity, runId,
						List.of(new PlannedLine(headId, "Absent fine", period, text, Amounts.text(amount), "0.00",
								Amounts.text(amount), null)));
				bills++;
				total = total.add(amount);
			}
			RunReport report = new RunReport(runId, "ABSENT_FINE", "DONE", counts.size(), bills, bills, already,
					Amounts.text(total), "0.00", List.of(), false);
			jdbc.update("update fee_run set status = 'DONE', report = ?::jsonb, finished_at = now() where id = ?",
					json.writeValueAsString(report), runId);
			audit.record("ABSENT_FINE_APPLIED", "fee_run", runId, null,
					Map.of("month", month.toString(), "bills", bills, "total", total.toPlainString()));
			return report;
		});
	}

	public RunReport lateFee(LateFeeInput in, String idempotencyKey) {
		String key = requireKey(idempotencyKey);
		YearRef year = yearContext.requireWritable();
		LocalDate today = Amounts.today(clock);
		LocalDate cutoff = in.dueOnOrBefore() == null ? today.minusDays(1) : in.dueOnOrBefore();
		if (!cutoff.isBefore(today)) {
			throw ApiException.businessRule("NOT_OVERDUE_YET", "Late fees apply only to bills whose due date has passed");
		}
		BigDecimal amount = in.amount() == null || in.amount().isBlank()
				? settings.money(SettingKey.LATE_FEE_AMOUNT).amount() : Amounts.parse(in.amount(), "Late fee", false);
		List<Long> excluded = in.excludeClassIds() == null ? List.of() : in.excludeClassIds();
		return transaction.execute(tx -> {
			ledger.lockKey("fee_run", key);
			var existing = jdbc.query("select report::text from fee_run where idempotency_key = ? and status = 'DONE'",
					(rs, i) -> rs.getString(1), key);
			if (!existing.isEmpty()) {
				RunReport done = json.readValue(existing.getFirst(), RunReport.class);
				return new RunReport(done.runId(), done.kind(), done.status(), done.students(), done.billsCreated(),
						done.linesCreated(), done.alreadyBilled(), done.totalBilled(), done.creditApplied(), List.of(),
						true);
			}
			List<Long> headIds = jdbc.queryForList(
					"select id from fee_head where system_code = 'LATE_FEE' and status = 'ACTIVE'", Long.class);
			if (headIds.isEmpty()) {
				throw ApiException.businessRule("LATE_FEE_OFF", "The Late fee head is switched off");
			}
			long headId = headIds.getFirst();
			Map<String, Object> params = Map.of("dueOnOrBefore", cutoff.toString(), "amount", amount.toPlainString(),
					"excludeClassIds", excluded);
			Long runId = jdbc.queryForObject("""
					insert into fee_run (school_id, kind, academic_year_id, idempotency_key, parameters, status, created_by)
					values (?, 'LATE_FEE', ?, ?, ?::jsonb, 'RUNNING', ?) returning id""", Long.class,
					TenantContext.requireSchoolId(), year.id(), key, json.writeValueAsString(params),
					CurrentActor.require().displayName());
			List<Object> args = new ArrayList<>(List.of(year.id(), Date.valueOf(cutoff), headId));
			StringBuilder sql = new StringBuilder("""
					select b.id, b.family_id, b.student_id from bill b
					where b.academic_year_id = ? and b.due_date <= ?
					  and (select coalesce(sum(v.balance), 0) from fee_line_status v
					       where v.bill_id = b.id and v.fee_head_id <> ?) > 0""");
			if (!excluded.isEmpty()) {
				sql.append(" and (b.class_id is null or b.class_id not in (").append(placeholders(excluded.size()))
					.append("))");
				args.addAll(excluded);
			}
			sql.append(" order by b.id");
			List<long[]> bills = jdbc.query(sql.toString(),
					(rs, i) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) }, args.toArray());
			int added = 0;
			int already = 0;
			long schoolId = TenantContext.requireSchoolId();
			for (long[] b : bills) {
				ledger.lockFamily(b[1]);
				int rows = jdbc.update("""
						insert into bill_line (school_id, bill_id, student_id, fee_head_id, period_key, description,
						                       gross, discount, net)
						values (?, ?, ?, ?, ?, 'Late fee', ?, 0, ?)
						on conflict on constraint bill_line_once do nothing""", schoolId, b[0], b[2], headId,
						"LATE-" + b[0], amount, amount);
				if (rows > 0) {
					added++;
				}
				else {
					already++;
				}
			}
			RunReport report = new RunReport(runId, "LATE_FEE", "DONE", bills.size(), 0, added, already,
					Amounts.text(amount.multiply(BigDecimal.valueOf(added))), "0.00", List.of(), false);
			jdbc.update("update fee_run set status = 'DONE', report = ?::jsonb, finished_at = now() where id = ?",
					json.writeValueAsString(report), runId);
			audit.record("LATE_FEE_APPLIED", "fee_run", runId, null,
					Map.of("bills", added, "alreadyCharged", already, "amount", amount.toPlainString()));
			return report;
		});
	}

	// ----------------------------------------------------------------- runs

	@Transactional(readOnly = true)
	public List<RunView> runs() {
		return jdbc.query("""
				select id, kind, status, parameters ->> 'title', created_by, started_at, finished_at, report::text
				from fee_run where academic_year_id = ? order by id desc limit 100""", (rs, i) -> {
			String report = rs.getString(8);
			return new RunView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
					rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
					report == null ? null : json.readValue(report, RunReport.class));
		}, yearContext.requireSelected().id());
	}
}
