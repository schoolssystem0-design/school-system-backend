package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only fee views: ledgers (FR 10.14), bills, fee status (FR 10.15), defaulters (FR 10.16) and reports
 * (FR 10.17). Every figure is computed from bill lines, approved adjustments and allocations.
 */
@Service
public class FeeQueries {

	public record LedgerEntry(LocalDate date, Instant at, String type, String reference, String studentName,
			String description, String debit, String credit, String balance) {
	}

	public record Ledger(String owed, String credit, String balance, List<LedgerEntry> entries) {
	}

	public record BillLineView(long id, long feeHeadId, String headName, String periodKey, String description,
			String gross, String discount, String discountNote, String net, String adjusted, String paid,
			String balance) {
	}

	public record BillView(long id, String billNo, long studentId, String studentName, String grNo, long familyId,
			String familyNo, String fatherName, String className, String sectionName, String title, LocalDate issueDate,
			LocalDate dueDate, LocalDate validityDate, String total, String paid, String balance, String status,
			List<BillLineView> lines) {
	}

	public record BillRow(long id, String billNo, long studentId, String studentName, String grNo, String className,
			String title, LocalDate issueDate, LocalDate dueDate, String total, String balance, String status) {
	}

	public record StatusRow(long studentId, String studentName, String grNo, long familyId, String familyNo,
			String fatherName, String fatherPhone, String className, String sectionName, String charged, String paid,
			String balance, LocalDate oldestDue, String status) {
	}

	public record DefaulterRow(long familyId, String familyNo, String fatherName, String fatherPhone, String children,
			String owed, String credit, String balance, LocalDate oldestDue, int monthsDue, LocalDate lastPayment,
			String lastFollowUp, LocalDate nextFollowUp) {
	}

	public record HeadReportRow(long feeHeadId, String headName, String charged, String discount, String collected,
			String outstanding) {
	}

	public record FamilyReceivable(long familyId, String familyNo, String fatherName, Map<String, String> byPeriod,
			String total) {
	}

	public record AdjustmentRow(long adjustmentId, LocalDate date, String studentName, String grNo, String headName,
			String description, String amount, String reason, String approvedBy) {
	}

	public record CreditRow(long familyId, String familyNo, String fatherName, String credit) {
	}

	public record MissingDiscountRow(long studentId, String studentName, String grNo, String className, int childOrder,
			String familyNo) {
	}

	private final JdbcTemplate jdbc;

	private final OpenLines openLines;

	private final YearContext yearContext;

	FeeQueries(JdbcTemplate jdbc, OpenLines openLines, YearContext yearContext) {
		this.jdbc = jdbc;
		this.openLines = openLines;
		this.yearContext = yearContext;
	}

	// ---------------------------------------------------------------- ledgers

	@Transactional(readOnly = true)
	public Ledger studentLedger(long studentId) {
		List<Long> f = jdbc.queryForList("select family_id from student where id = ?", Long.class, studentId);
		if (f.isEmpty()) {
			throw ApiException.notFound("Student");
		}
		return ledger("bl.student_id = ?", studentId, f.getFirst(), studentId);
	}

	@Transactional(readOnly = true)
	public Ledger familyLedger(long familyId) {
		Long n = jdbc.queryForObject("select count(*) from family where id = ?", Long.class, familyId);
		if (n == null || n == 0) {
			throw ApiException.notFound("Family");
		}
		return ledger("b.family_id = ?", familyId, familyId, null);
	}

	/**
	 * Charges (debit), corrections and payments (credit) in date order with a running balance. For a family, payments
	 * are shown at their full amount, so unallocated advance shows as a negative balance.
	 */
	private Ledger ledger(String lineFilter, long id, long familyId, Long studentId) {
		List<Object[]> rows = new ArrayList<>();
		jdbc.query("""
				select b.issue_date, bl.created_at, 'CHARGE', b.bill_no, s.name, bl.description, bl.net
				from bill_line bl join bill b on b.id = bl.bill_id join student s on s.id = bl.student_id
				where\s""" + lineFilter, rs -> {
			rows.add(new Object[] { rs.getDate(1).toLocalDate(), rs.getTimestamp(2).toInstant(), rs.getString(3),
					rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7) });
		}, id);
		jdbc.query("""
				select d.decided_at::date, d.decided_at, a.kind, b.bill_no, s.name,
				       bl.description || ' - ' || a.reason, a.amount
				from fee_adjustment a join fee_adjustment_decision d on d.adjustment_id = a.id and d.decision = 'APPROVED'
				join bill_line bl on bl.id = a.bill_line_id join bill b on b.id = bl.bill_id
				join student s on s.id = bl.student_id
				where\s""" + lineFilter, rs -> {
			rows.add(new Object[] { rs.getDate(1).toLocalDate(), rs.getTimestamp(2).toInstant(), rs.getString(3),
					rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7) });
		}, id);
		if (studentId == null) {
			jdbc.query("""
					select p.payment_date, p.created_at, case when p.amount < 0 then 'REVERSAL' else 'PAYMENT' end,
					       coalesce(p.receipt_no, 'Reversal'), s.name, p.method || coalesce(' - ' || p.reversal_reason, ''),
					       -p.amount
					from payment p left join student s on s.id = p.student_id where p.family_id = ?""", rs -> {
				rows.add(new Object[] { rs.getDate(1).toLocalDate(), rs.getTimestamp(2).toInstant(), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7) });
			}, familyId);
		}
		else {
			// For one student: what was allocated to their lines (by payment or by applied credit).
			jdbc.query("""
					select pa.allocation_date, pa.created_at,
					       case pa.kind when 'CREDIT' then 'CREDIT_USED' when 'REVERSAL' then 'REVERSAL'
					                    when 'RELEASE' then 'MOVED_TO_CREDIT' else 'PAYMENT' end,
					       coalesce(p.receipt_no, r.receipt_no, 'Reversal'), s.name, bl.description, -pa.amount
					from payment_allocation pa join payment p on p.id = pa.payment_id
					left join payment r on r.id = p.reverses_payment_id
					join bill_line bl on bl.id = pa.bill_line_id join student s on s.id = bl.student_id
					where bl.student_id = ?""", rs -> {
				rows.add(new Object[] { rs.getDate(1).toLocalDate(), rs.getTimestamp(2).toInstant(), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7) });
			}, studentId);
		}
		rows.sort((a, b) -> {
			int c = ((LocalDate) a[0]).compareTo((LocalDate) b[0]);
			return c != 0 ? c : ((Instant) a[1]).compareTo((Instant) b[1]);
		});
		BigDecimal running = BigDecimal.ZERO;
		List<LedgerEntry> entries = new ArrayList<>();
		for (Object[] r : rows) {
			BigDecimal amount = (BigDecimal) r[6];
			running = running.add(amount);
			entries.add(new LedgerEntry((LocalDate) r[0], (Instant) r[1], (String) r[2], (String) r[3], (String) r[4],
					(String) r[5], amount.signum() > 0 ? Amounts.text(amount) : null,
					amount.signum() < 0 ? Amounts.text(amount.negate()) : null, Amounts.text(running)));
		}
		BigDecimal owed = studentId == null ? openLines.owed(familyId)
				: Amounts.nz(jdbc.queryForObject(
						"select sum(balance) from fee_line_status where student_id = ? and balance > 0", BigDecimal.class,
						studentId));
		BigDecimal credit = studentId == null ? openLines.creditTotal(familyId)
				: openLines.credits(familyId).stream()
					.filter(c -> studentId.equals(c.studentId()))
					.map(OpenLines.Credit::remaining)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new Ledger(Amounts.text(owed), Amounts.text(credit), Amounts.text(owed.subtract(credit)), entries);
	}

	// ------------------------------------------------------------------ bills

	@Transactional(readOnly = true)
	public BillView bill(long id) {
		List<BillView> bills = jdbc.query("""
				select b.id, b.bill_no, b.student_id, s.name, s.gr_no, b.family_id, f.family_no, f.father_name, c.name,
				       sec.name, b.title, b.issue_date, b.due_date, b.validity_date
				from bill b join student s on s.id = b.student_id join family f on f.id = b.family_id
				left join school_class c on c.id = b.class_id left join section sec on sec.id = b.section_id
				where b.id = ?""", (rs, i) -> {
			List<BillLineView> lines = lines(rs.getLong(1));
			BigDecimal total = sum(lines, true);
			BigDecimal balance = lines.stream().map(l -> new BigDecimal(l.balance())).reduce(BigDecimal.ZERO,
					BigDecimal::add);
			return new BillView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
					rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
					rs.getDate(12).toLocalDate(), rs.getDate(13).toLocalDate(), rs.getDate(14).toLocalDate(),
					Amounts.text(total), Amounts.text(total.subtract(balance)), Amounts.text(balance),
					status(total, balance), lines);
		}, id);
		if (bills.isEmpty()) {
			throw ApiException.notFound("Bill");
		}
		return bills.getFirst();
	}

	List<BillLineView> lines(long billId) {
		return jdbc.query("""
				select v.bill_line_id, v.fee_head_id, h.name, v.period_key, v.description, v.gross, v.discount,
				       bl.discount_note, v.net, v.adjusted, v.paid, v.balance
				from fee_line_status v join fee_head h on h.id = v.fee_head_id join bill_line bl on bl.id = v.bill_line_id
				where v.bill_id = ? order by h.display_order, v.bill_line_id""",
				(rs, i) -> new BillLineView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
						rs.getString(5), Amounts.text(rs.getBigDecimal(6)), Amounts.text(rs.getBigDecimal(7)),
						rs.getString(8), Amounts.text(rs.getBigDecimal(9)), Amounts.text(rs.getBigDecimal(10)),
						Amounts.text(rs.getBigDecimal(11)), Amounts.text(rs.getBigDecimal(12))),
				billId);
	}

	private static BigDecimal sum(List<BillLineView> lines, boolean charged) {
		return lines.stream()
			.map(l -> new BigDecimal(l.net()).add(new BigDecimal(l.adjusted())))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	static String status(BigDecimal total, BigDecimal balance) {
		if (total.signum() == 0) {
			return "CANCELLED";
		}
		if (balance.signum() <= 0) {
			return "PAID";
		}
		return balance.compareTo(total) < 0 ? "PARTIAL" : "UNPAID";
	}

	/** Bills of the selected year, with filters. {@code status}: UNPAID, PARTIAL, PAID, CANCELLED. */
	@Transactional(readOnly = true)
	public PageResponse<BillRow> bills(Long studentId, Long familyId, Long classId, Long sectionId, Long runId,
			String status, String q, int page, int size) {
		long yearId = yearContext.requireSelected().id();
		List<Object> args = new ArrayList<>(List.of(yearId));
		StringBuilder where = new StringBuilder(" where b.academic_year_id = ?");
		eq(where, args, "b.student_id", studentId);
		eq(where, args, "b.family_id", familyId);
		eq(where, args, "b.class_id", classId);
		eq(where, args, "b.section_id", sectionId);
		eq(where, args, "b.fee_run_id", runId);
		if (q != null && !q.isBlank()) {
			where.append(" and (b.bill_no ilike ? or s.name ilike ? or s.gr_no ilike ?)");
			String like = "%" + q.trim() + "%";
			args.addAll(List.of(like, like, like));
		}
		String base = """
				select b.id, b.bill_no, b.student_id, s.name, s.gr_no, c.name, b.title, b.issue_date, b.due_date,
				       t.total, t.balance,
				       case when t.total = 0 then 'CANCELLED' when t.balance <= 0 then 'PAID'
				            when t.balance < t.total then 'PARTIAL' else 'UNPAID' end as status
				from bill b join student s on s.id = b.student_id left join school_class c on c.id = b.class_id
				join lateral (select coalesce(sum(v.net + v.adjusted), 0) as total, coalesce(sum(v.balance), 0) as balance
				              from fee_line_status v where v.bill_id = b.id) t on true""" + where;
		String filtered = "select * from (" + base + ") x" + (status == null || status.isBlank() ? "" : " where status = ?");
		List<Object> all = new ArrayList<>(args);
		if (status != null && !status.isBlank()) {
			all.add(status);
		}
		Long total = jdbc.queryForObject("select count(*) from (" + filtered + ") y", Long.class, all.toArray());
		List<Object> pageArgs = new ArrayList<>(all);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<BillRow> rows = jdbc.query(filtered + " order by id desc limit ? offset ?",
				(rs, i) -> new BillRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
						rs.getString(6), rs.getString(7), rs.getDate(8).toLocalDate(), rs.getDate(9).toLocalDate(),
						Amounts.text(rs.getBigDecimal(10)), Amounts.text(rs.getBigDecimal(11)), rs.getString(12)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	// ------------------------------------------------------------- fee status

	/** Per student: charged and paid in the selected year, balance across all years (FR 10.15). */
	@Transactional(readOnly = true)
	public PageResponse<StatusRow> status(Long classId, Long sectionId, String status, String q, int page, int size) {
		var year = yearContext.requireSelected();
		long yearId = year.id();
		// The class each student was in on the last day that counts: today, or the year's end for a past year.
		LocalDate now = LocalDate.now(Amounts.ZONE);
		LocalDate today = now.isAfter(year.endDate()) ? year.endDate() : now.isBefore(year.startDate()) ? year.startDate() : now;
		List<Object> args = new ArrayList<>(List.of(yearId, yearId, Date.valueOf(today), Date.valueOf(today)));
		StringBuilder where = new StringBuilder(" where s.status = 'ACTIVE'");
		eq(where, args, "cur.class_id", classId);
		eq(where, args, "cur.section_id", sectionId);
		if (q != null && !q.isBlank()) {
			where.append(" and (s.name ilike ? or s.gr_no ilike ? or f.father_name ilike ? or f.family_no ilike ?)");
			String like = "%" + q.trim() + "%";
			args.addAll(List.of(like, like, like, like));
		}
		String base = """
				select s.id, s.name, s.gr_no, f.id as family_id, f.family_no, f.father_name, f.father_phone, c.name as class_name,
				       sec.name as section_name, c.display_order,
				       coalesce(y.charged, 0) as charged, coalesce(y.paid, 0) as paid, coalesce(a.balance, 0) as balance,
				       a.oldest_due,
				       case when coalesce(a.balance, 0) <= 0 then 'PAID'
				            when coalesce(y.paid, 0) > 0 then 'PARTIAL' else 'UNPAID' end as fee_status
				from student s join family f on f.id = s.family_id
				left join lateral (select sum(v.net + v.adjusted) as charged, sum(v.paid) as paid from fee_line_status v
				                   where v.student_id = s.id and v.academic_year_id = ?) y on true
				left join lateral (select sum(v.balance) filter (where v.balance > 0) as balance,
				                          min(v.due_date) filter (where v.balance > 0) as oldest_due
				                   from fee_line_status v where v.student_id = s.id) a on true
				join lateral (select e.class_id, e.section_id from enrolment e where e.student_id = s.id
				                and e.academic_year_id = ? and e.effective_from <= ?
				                and (e.effective_to is null or e.effective_to >= ?)
				              order by e.effective_from desc limit 1) cur on true
				join school_class c on c.id = cur.class_id join section sec on sec.id = cur.section_id""" + where;
		String outer = "select * from (" + base + ") z"
				+ (status == null || status.isBlank() ? "" : " where fee_status = ?");
		List<Object> all = new ArrayList<>(args);
		if (status != null && !status.isBlank()) {
			all.add(status);
		}
		Long total = jdbc.queryForObject("select count(*) from (" + outer + ") t", Long.class, all.toArray());
		List<Object> pageArgs = new ArrayList<>(all);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<StatusRow> rows = jdbc.query(outer + " order by display_order, section_name, name limit ? offset ?",
				(rs, i) -> new StatusRow(rs.getLong("id"), rs.getString("name"), rs.getString("gr_no"),
						rs.getLong("family_id"), rs.getString("family_no"), rs.getString("father_name"),
						rs.getString("father_phone"), rs.getString("class_name"), rs.getString("section_name"),
						Amounts.text(rs.getBigDecimal("charged")), Amounts.text(rs.getBigDecimal("paid")),
						Amounts.text(rs.getBigDecimal("balance")),
						rs.getDate("oldest_due") == null ? null : rs.getDate("oldest_due").toLocalDate(),
						rs.getString("fee_status")),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	// ------------------------------------------------------------- defaulters

	/** Families owing at least {@code minBalance}, optionally overdue for {@code minDaysOverdue} days (FR 10.16). */
	@Transactional(readOnly = true)
	public List<DefaulterRow> defaulters(String minBalance, Integer minDaysOverdue, Long classId) {
		BigDecimal min = minBalance == null || minBalance.isBlank() ? new BigDecimal("0.01")
				: Amounts.parse(minBalance, "Minimum balance", true);
		LocalDate today = LocalDate.now(Amounts.ZONE);
		LocalDate dueBefore = today.minusDays(minDaysOverdue == null ? 0 : minDaysOverdue);
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(today)));
		String classFilter = "";
		if (classId != null) {
			classFilter = " " + """
					and exists (select 1 from student s2 join enrolment e2 on e2.student_id = s2.id
					             where s2.family_id = f.id and s2.status = 'ACTIVE' and e2.class_id = ?
					               and e2.effective_to is null)""";
			args.add(classId);
		}
		args.add(Date.valueOf(dueBefore));
		args.add(min);
		return jdbc.query("""
				select * from (
				  select f.id, f.family_no, f.father_name, f.father_phone,
				         (select string_agg(s.name, ', ' order by s.name) from student s
				          where s.family_id = f.id and s.status = 'ACTIVE') as children,
				         o.owed, coalesce(c.credit, 0) as credit, o.owed - coalesce(c.credit, 0) as balance, o.oldest_due,
				         o.periods,
				         (select max(p.payment_date) from payment p where p.family_id = f.id and p.amount > 0) as last_payment,
				         fu.remark, fu.next_date
				  from family f
				  join lateral (select sum(v.balance) as owed, min(v.due_date) as oldest_due,
				                       count(distinct to_char(v.due_date, 'YYYY-MM')) as periods
				                from fee_line_status v where v.family_id = f.id and v.balance > 0 and v.due_date < ?) o
				       on o.owed is not null
				  left join lateral (select sum(remaining) as credit from payment_credit pc
				                     where pc.family_id = f.id and pc.remaining > 0) c on true
				  left join lateral (select remark, next_date from fee_followup ff where ff.family_id = f.id
				                     order by ff.id desc limit 1) fu on true
				  where 1 = 1""" + classFilter + " " + """
				) d where d.oldest_due <= ? and d.balance >= ? order by d.balance desc limit 1000""",
				(rs, i) -> new DefaulterRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), Amounts.text(rs.getBigDecimal(6)), Amounts.text(rs.getBigDecimal(7)),
						Amounts.text(rs.getBigDecimal(8)), rs.getDate(9).toLocalDate(), rs.getInt(10),
						rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(), rs.getString(12),
						rs.getDate(13) == null ? null : rs.getDate(13).toLocalDate()),
				args.toArray());
	}

	// ---------------------------------------------------------------- reports

	/** Charged, discount, collected and outstanding per fee head for bills issued in the range (FR 10.17). */
	@Transactional(readOnly = true)
	public List<HeadReportRow> headReport(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select h.id, h.name, coalesce(sum(v.gross), 0), coalesce(sum(v.discount), 0), coalesce(sum(v.paid), 0),
				       coalesce(sum(v.balance) filter (where v.balance > 0), 0)
				from fee_line_status v join fee_head h on h.id = v.fee_head_id
				where v.issue_date between ? and ?
				group by h.id, h.name, h.display_order order by h.display_order""",
				(rs, i) -> new HeadReportRow(rs.getLong(1), rs.getString(2), Amounts.text(rs.getBigDecimal(3)),
						Amounts.text(rs.getBigDecimal(4)), Amounts.text(rs.getBigDecimal(5)),
						Amounts.text(rs.getBigDecimal(6))),
				Date.valueOf(from), Date.valueOf(to));
	}

	/** Money received per fee head in a date range: Σ allocations dated in the range (matches fee income). */
	@Transactional(readOnly = true)
	public List<HeadReportRow> collectionReport(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select h.id, h.name, '0', '0', coalesce(sum(pa.amount), 0), '0'
				from payment_allocation pa join bill_line bl on bl.id = pa.bill_line_id join fee_head h on h.id = bl.fee_head_id
				where pa.allocation_date between ? and ?
				group by h.id, h.name, h.display_order order by h.display_order""",
				(rs, i) -> new HeadReportRow(rs.getLong(1), rs.getString(2), "0.00", "0.00",
						Amounts.text(rs.getBigDecimal(5)), "0.00"),
				Date.valueOf(from), Date.valueOf(to));
	}

	/** Outstanding per family, by month of the due date (FR 10.17 family month-wise receivables). */
	@Transactional(readOnly = true)
	public List<FamilyReceivable> familyReceivables() {
		Map<Long, FamilyReceivable> byFamily = new LinkedHashMap<>();
		jdbc.query("""
				select f.id, f.family_no, f.father_name, to_char(v.due_date, 'YYYY-MM'), sum(v.balance)
				from fee_line_status v join family f on f.id = v.family_id
				where v.balance > 0
				group by f.id, f.family_no, f.father_name, to_char(v.due_date, 'YYYY-MM')
				order by f.family_no""", rs -> {
			long id = rs.getLong(1);
			FamilyReceivable current = byFamily.computeIfAbsent(id,
					k -> new FamilyReceivable(k, rsString(rs, 2), rsString(rs, 3), new TreeMap<>(), "0.00"));
			current.byPeriod().put(rs.getString(4), Amounts.text(rs.getBigDecimal(5)));
		});
		List<FamilyReceivable> list = new ArrayList<>();
		for (FamilyReceivable r : byFamily.values()) {
			BigDecimal total = r.byPeriod().values().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO,
					BigDecimal::add);
			list.add(new FamilyReceivable(r.familyId(), r.familyNo(), r.fatherName(), r.byPeriod(), Amounts.text(total)));
		}
		return list;
	}

	private static String rsString(java.sql.ResultSet rs, int column) {
		try {
			return rs.getString(column);
		}
		catch (java.sql.SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Approved write-offs (bad debts) and waivers. */
	@Transactional(readOnly = true)
	public List<AdjustmentRow> writeOffs(String kind) {
		return jdbc.query("""
				select a.id, d.decided_at::date, s.name, s.gr_no, h.name, bl.description, a.amount, a.reason, d.decided_by
				from fee_adjustment a join fee_adjustment_decision d on d.adjustment_id = a.id and d.decision = 'APPROVED'
				join bill_line bl on bl.id = a.bill_line_id join student s on s.id = bl.student_id
				join fee_head h on h.id = bl.fee_head_id
				where a.kind = ? order by d.decided_at desc""",
				(rs, i) -> new AdjustmentRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), Amounts.text(rs.getBigDecimal(7)), rs.getString(8),
						rs.getString(9)),
				kind);
	}

	/** Families holding advance credit (money paid but not yet used) — the negative-balance report. */
	@Transactional(readOnly = true)
	public List<CreditRow> credits() {
		return jdbc.query("""
				select f.id, f.family_no, f.father_name, sum(pc.remaining)
				from payment_credit pc join family f on f.id = pc.family_id
				where pc.remaining > 0 group by f.id, f.family_no, f.father_name order by sum(pc.remaining) desc""",
				(rs, i) -> new CreditRow(rs.getLong(1), rs.getString(2), rs.getString(3),
						Amounts.text(rs.getBigDecimal(4))));
	}

	/**
	 * Students who qualify for a sibling discount by their position in the family but whose latest bill in the
	 * selected year has no discount on any line ("should have a discount but doesn't", FR 10.4).
	 */
	@Transactional(readOnly = true)
	public List<MissingDiscountRow> missingSiblingDiscounts() {
		long yearId = yearContext.requireSelected().id();
		return jdbc.query("""
				select * from (
				  select s.id, s.name, s.gr_no,
				         (select c.name from enrolment e join school_class c on c.id = e.class_id
				          where e.student_id = s.id order by e.effective_from desc limit 1) as class_name,
				         (select count(*) from student o where o.family_id = s.family_id and o.status = 'ACTIVE'
				            and (o.admission_date < s.admission_date or (o.admission_date = s.admission_date and o.id <= s.id)))
				           as child_order,
				         f.family_no,
				         (select b.id from bill b where b.student_id = s.id and b.academic_year_id = ? order by b.id desc limit 1)
				           as last_bill
				  from student s join family f on f.id = s.family_id where s.status = 'ACTIVE'
				) x
				where x.child_order >= (select coalesce(min(child_order), 1000) from sibling_discount_rule)
				  and x.last_bill is not null
				  and not exists (select 1 from bill_line bl where bl.bill_id = x.last_bill and bl.discount > 0)
				order by x.family_no, x.child_order""",
				(rs, i) -> new MissingDiscountRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getInt(5), rs.getString(6)),
				yearId);
	}

	private static void eq(StringBuilder where, List<Object> args, String column, Long value) {
		if (value != null) {
			where.append(" and ").append(column).append(" = ?");
			args.add(value);
		}
	}
}
