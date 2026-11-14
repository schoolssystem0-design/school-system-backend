package com.selectcode.sms.reports.app;

import static com.selectcode.sms.reports.app.ReportDoc.money;
import static com.selectcode.sms.reports.app.ReportDoc.number;
import static com.selectcode.sms.reports.app.ReportDoc.percent;
import static com.selectcode.sms.reports.app.ReportDoc.text;
import static com.selectcode.sms.reports.app.ReportRenderer.row;

import com.selectcode.sms.reports.app.ReportDoc.Section;
import com.selectcode.sms.shared.attendance.AttendancePercent;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The super admin's school-by-school comparison for any period (P-9, QA-PLT-011/013). Runs across all schools, so it is
 * reachable only under /platform by the super admin.
 */
@Service
public class CrossSchoolReports {

	public record SchoolFigures(long schoolId, String code, String name, String status, int students, String attendancePercent,
			String billed, String collected, String outstanding, int defaulterFamilies, String income, String expense,
			String testAverage) {
	}

	public record CrossSchoolReport(LocalDate from, LocalDate to, List<SchoolFigures> schools, SchoolFigures total) {
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final Clock clock;

	private final TransactionTemplate transaction;

	CrossSchoolReports(JdbcTemplate jdbc, AuditService audit, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public CrossSchoolReport report(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw ApiException.businessRule("DATES_INVALID", "The last day is before the first day");
		}
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> build(from, to)));
	}

	private interface MapFiller {

		void fill(java.sql.ResultSet rs) throws java.sql.SQLException;
	}

	private void each(String sql, MapFiller filler, Object... args) {
		jdbc.query(sql, rs -> {
			filler.fill(rs);
		}, args);
	}

	private CrossSchoolReport build(LocalDate from, LocalDate to) {
		Date f = Date.valueOf(from);
		Date t = Date.valueOf(to);
		Date today = Date.valueOf(MoneyInput.today(clock));
		Map<Long, Integer> students = new HashMap<>();
		each("""
				select e.school_id, count(distinct e.student_id) from enrolment e join student s on s.id = e.student_id
				where s.status = 'ACTIVE' and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				group by e.school_id""", rs -> students.put(rs.getLong(1), rs.getInt(2)), t, t);
		Map<Long, int[]> attendance = new HashMap<>();
		each("""
				select school_id, count(*) filter (where status = 'P'), count(*) filter (where status = 'L'),
				       count(*) filter (where status = 'HD'), count(*), count(*) filter (where status = 'LV')
				from student_attendance where att_date between ? and ? group by school_id""",
				rs -> attendance.put(rs.getLong(1), new int[] { rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6) }), f, t);
		Map<Long, BigDecimal> billed = new HashMap<>();
		each("""
				select school_id, sum(net + adjusted) from fee_line_status where issue_date between ? and ? group by school_id""",
				rs -> billed.put(rs.getLong(1), rs.getBigDecimal(2)), f, t);
		Map<Long, BigDecimal> collected = new HashMap<>();
		each("select school_id, sum(amount) from payment where payment_date between ? and ? group by school_id",
				rs -> collected.put(rs.getLong(1), rs.getBigDecimal(2)), f, t);
		Map<Long, BigDecimal> outstanding = new HashMap<>();
		Map<Long, Integer> defaulters = new HashMap<>();
		each("""
				select school_id, sum(balance), count(distinct family_id) filter (where due_date < ?)
				from fee_line_status where balance > 0 group by school_id""", rs -> {
			outstanding.put(rs.getLong(1), rs.getBigDecimal(2));
			defaulters.put(rs.getLong(1), rs.getInt(3));
		}, today);
		Map<Long, BigDecimal> income = new HashMap<>();
		Map<Long, BigDecimal> expense = new HashMap<>();
		each("""
				select l.school_id, coalesce(sum(l.credit - l.debit) filter (where a.account_type = 'INCOME'), 0),
				       coalesce(sum(l.debit - l.credit) filter (where a.account_type = 'EXPENSE'), 0)
				from journal_line l join journal_entry j on j.id = l.entry_id join ledger_account a on a.id = l.ledger_account_id
				where j.entry_date between ? and ? and a.account_type in ('INCOME', 'EXPENSE') group by l.school_id""", rs -> {
			income.put(rs.getLong(1), rs.getBigDecimal(2));
			expense.put(rs.getLong(1), rs.getBigDecimal(3));
		}, f, t);
		Map<Long, BigDecimal[]> tests = new HashMap<>();
		each("""
				select t.school_id, sum(coalesce(m.obtained, 0)), sum(t.max_marks)
				from class_test t join test_mark m on m.class_test_id = t.id
				where t.test_date between ? and ? group by t.school_id""",
				rs -> tests.put(rs.getLong(1), new BigDecimal[] { rs.getBigDecimal(2), rs.getBigDecimal(3) }), f, t);

		List<SchoolFigures> rows = jdbc.query("select id, code, name, status from school order by name", (rs, i) -> {
			long id = rs.getLong(1);
			int[] a = attendance.get(id);
			BigDecimal[] m = tests.get(id);
			return new SchoolFigures(id, rs.getString(2), rs.getString(3), rs.getString(4), students.getOrDefault(id, 0),
					a == null ? null : AttendancePercent.of(a[0], a[1], a[2], a[3], a[4]), amount(billed.get(id)), amount(collected.get(id)),
					amount(outstanding.get(id)), defaulters.getOrDefault(id, 0), amount(income.get(id)), amount(expense.get(id)),
					m == null ? null : percentOf(m[0], m[1]));
		});

		// Totals are worked from the underlying figures, not by averaging each school's percentage
		int[] allAttendance = new int[5];
		attendance.values().forEach(a -> {
			for (int i = 0; i < 5; i++) {
				allAttendance[i] += a[i];
			}
		});
		BigDecimal[] allTests = { BigDecimal.ZERO, BigDecimal.ZERO };
		tests.values().forEach(m -> {
			allTests[0] = allTests[0].add(m[0]);
			allTests[1] = allTests[1].add(m[1]);
		});
		SchoolFigures total = new SchoolFigures(0, null, "All schools", null, rows.stream().mapToInt(SchoolFigures::students).sum(),
				attendance.isEmpty() ? null : AttendancePercent.of(allAttendance[0], allAttendance[1], allAttendance[2], allAttendance[3], allAttendance[4]),
				sum(billed), sum(collected), sum(outstanding), rows.stream().mapToInt(SchoolFigures::defaulterFamilies).sum(),
				sum(income), sum(expense), allTests[1].signum() == 0 ? null : percentOf(allTests[0], allTests[1]));
		return new CrossSchoolReport(from, to, rows, total);
	}

	private static String amount(BigDecimal v) {
		return MoneyInput.text(v == null ? BigDecimal.ZERO : v);
	}

	private static String sum(Map<Long, BigDecimal> values) {
		return amount(values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
	}

	private static String percentOf(BigDecimal got, BigDecimal max) {
		return got.multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP).toPlainString();
	}

	/** The file: a comparison sheet, then one sheet per school (QA-PLT-013). The export is in the platform audit log. */
	public byte[] export(LocalDate from, LocalDate to, String format) {
		CrossSchoolReport report = report(from, to);
		List<String> heading = List.of(PdfKit.date(from) + " to " + PdfKit.date(to));
		List<List<String>> compare = new ArrayList<>();
		report.schools().forEach(s -> compare.add(values(s)));
		compare.add(values(report.total()));
		List<Section> sections = new ArrayList<>();
		sections.add(new Section("All schools", "Outstanding and defaulters are as of today; the rest are for the period. Absent counts as 0 in the test average.",
				List.of(text("School"), number("Students"), percent("Attendance %"), money("Billed"), money("Collected"), money("Outstanding"),
						number("Defaulter families"), money("Income"), money("Expense"), percent("Test average %")), compare));
		if ("xlsx".equals(format)) {
			for (SchoolFigures s : report.schools()) {
				sections.add(new Section(s.code() + " " + s.name(), null, List.of(text("Measure"), text("Value")),
						List.of(row("Status", s.status()), row("Students", s.students()), row("Attendance %", s.attendancePercent()),
								row("Billed", s.billed()), row("Collected", s.collected()), row("Outstanding", s.outstanding()),
								row("Defaulter families", s.defaulterFamilies()), row("Income", s.income()), row("Expense", s.expense()),
								row("Test average %", s.testAverage()))));
			}
		}
		ReportDoc doc = new ReportDoc("ALL SCHOOLS REPORT", heading, sections);
		byte[] file = switch (format) {
			case "pdf" -> ReportRenderer.pdf(doc, new Letterhead("SelectCode", null, null, null, null, null, null, null, null, List.of()));
			case "xlsx" -> ReportRenderer.xlsx(doc, true);
			default -> throw ApiException.businessRule("FORMAT_INVALID", "Choose PDF or Excel");
		};
		transaction.executeWithoutResult(tx -> audit.recordPlatform("CROSS_SCHOOL_REPORT_EXPORTED", null,
				Map.of("from", from.toString(), "to", to.toString(), "format", format)));
		return file;
	}

	private static List<String> values(SchoolFigures s) {
		return row(s.name(), s.students(), s.attendancePercent(), s.billed(), s.collected(), s.outstanding(), s.defaulterFamilies(),
				s.income(), s.expense(), s.testAverage());
	}
}
