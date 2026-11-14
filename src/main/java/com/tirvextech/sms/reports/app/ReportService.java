package com.selectcode.sms.reports.app;

import static com.selectcode.sms.reports.app.ReportDoc.money;
import static com.selectcode.sms.reports.app.ReportDoc.number;
import static com.selectcode.sms.reports.app.ReportDoc.percent;
import static com.selectcode.sms.reports.app.ReportDoc.text;
import static com.selectcode.sms.reports.app.ReportRenderer.row;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.academics.AcademicStructure.SectionRef;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.reports.app.ReportDoc.Column;
import com.selectcode.sms.reports.app.ReportDoc.Section;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.attendance.AttendancePercent;
import com.selectcode.sms.shared.attendance.RepeatedAbsenceCount;
import com.selectcode.sms.shared.attendance.RepeatedAbsenceCount.RepeatedStudent;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly and monthly reports for a student, a teacher and a class (FR 15.1–15.4, 15.7). A report covers dates inside one
 * academic year, so years never mix, and is compared with the period before it. Teachers get reports only for the
 * sections they teach; fee figures appear only for users with fee access and the "all classes" scope.
 */
@Service
public class ReportService {

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	private final JdbcTemplate jdbc;

	private final YearContext yearContext;

	private final AcademicStructure structure;

	private final SchoolUserAdministration users;

	private final WorkingDayCalendar calendar;

	private final RepeatedAbsenceCount repeated;

	private final SettingsService settings;

	private final Clock clock;

	ReportService(JdbcTemplate jdbc, YearContext yearContext, AcademicStructure structure, SchoolUserAdministration users,
			WorkingDayCalendar calendar, RepeatedAbsenceCount repeated, SettingsService settings, Clock clock) {
		this.jdbc = jdbc;
		this.yearContext = yearContext;
		this.structure = structure;
		this.users = users;
		this.calendar = calendar;
		this.repeated = repeated;
		this.settings = settings;
		this.clock = clock;
	}

	// ----------------------------------------------------------------- period

	/** The chosen dates and the period before: the previous calendar month for a whole month, else the same length. */
	record Period(YearRef year, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {

		boolean hasPrevious() {
			return previousTo != null;
		}

		String label() {
			if (from.getDayOfMonth() == 1 && to.equals(YearMonth.from(from).atEndOfMonth())) {
				return "Month of " + MONTH.format(from);
			}
			String range = PdfKit.date(from) + " to " + PdfKit.date(to);
			return ChronoUnit.DAYS.between(from, to) == 6 ? "Week " + range : range;
		}

		String previousLabel() {
			return hasPrevious() ? PdfKit.date(previousFrom) + " to " + PdfKit.date(previousTo) : "No earlier period this year";
		}
	}

	private Period period(LocalDate from, LocalDate to) {
		YearRef year = yearContext.requireSelected();
		if (to.isBefore(from)) {
			throw ApiException.businessRule("DATES_INVALID", "The last day is before the first day");
		}
		if (from.isBefore(year.startDate()) || to.isAfter(year.endDate())) {
			throw ApiException.businessRule("PERIOD_OUTSIDE_YEAR", "Choose dates within academic year " + year.name()
					+ " (" + PdfKit.date(year.startDate()) + " to " + PdfKit.date(year.endDate()) + ")");
		}
		boolean wholeMonth = from.getDayOfMonth() == 1 && to.equals(YearMonth.from(from).atEndOfMonth());
		LocalDate previousTo = from.minusDays(1);
		LocalDate previousFrom = wholeMonth ? previousTo.withDayOfMonth(1)
				: previousTo.minusDays(ChronoUnit.DAYS.between(from, to));
		if (previousFrom.isBefore(year.startDate())) {
			previousFrom = year.startDate();
		}
		boolean none = previousTo.isBefore(year.startDate());
		return new Period(year, from, to, none ? null : previousFrom, none ? null : previousTo);
	}

	private LocalDate lastDay(Period p) {
		LocalDate today = MoneyInput.today(clock);
		return p.to().isAfter(today) ? (p.from().isAfter(today) ? p.from() : today) : p.to();
	}

	// ----------------------------------------------------------------- access

	/** Sections the user may report on, or null for all. */
	private Set<Long> allowedSections(Period p) {
		if (DataScope.current() == DataScope.ALL) {
			return null;
		}
		var actor = CurrentActor.require();
		Long staff = actor.actorType() == ActorType.USER && DataScope.current() != DataScope.SELF
				? users.staffIdOfUser(actor.actorId()).orElse(null)
				: null;
		return staff == null ? Set.of() : structure.sectionsTaughtBy(staff, p.year().id(), lastDay(p));
	}

	private static boolean feesAllowed() {
		return DataScope.current() == DataScope.ALL && CurrentActor.hasPermission(Permissions.FEES_VIEW);
	}

	private Array ids(List<Long> values) {
		return jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint", values.toArray()));
	}

	// ------------------------------------------------------------- attendance

	private record Counts(int present, int late, int half, int absent, int leave) {

		int marked() {
			return present + late + half + absent + leave;
		}

		String percent() {
			return AttendancePercent.of(present, late, half, marked(), leave);
		}
	}

	private Counts counts(String table, String where, Object... args) {
		return jdbc.queryForObject("""
				select count(*) filter (where status = 'P'), count(*) filter (where status = 'L'),
				       count(*) filter (where status = 'HD'), count(*) filter (where status = 'A'),
				       count(*) filter (where status = 'LV')
				from %s where %s""".formatted(table, where),
				(rs, i) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), args);
	}

	private static String change(String now, String before) {
		if (now == null || before == null) {
			return null;
		}
		BigDecimal d = new BigDecimal(now).subtract(new BigDecimal(before));
		return (d.signum() > 0 ? "+" : "") + d.stripTrailingZeros().toPlainString();
	}

	private static List<String> compared(String measure, Object now, Object before) {
		String n = now == null ? null : now.toString();
		String b = before == null ? null : before.toString();
		return row(measure, n, b, change(n, b));
	}

	private static final List<Column> COMPARED = List.of(text("Measure"), number("This period"), number("Previous period"),
			number("Change"));

	private Section attendanceSection(String table, String column, long id, Period p) {
		String where = column + " = ? and att_date between ? and ?";
		Counts now = counts(table, where, id, Date.valueOf(p.from()), Date.valueOf(p.to()));
		Counts before = p.hasPrevious() ? counts(table, where, id, Date.valueOf(p.previousFrom()), Date.valueOf(p.previousTo()))
				: null;
		List<List<String>> rows = new ArrayList<>();
		rows.add(compared("Present", now.present(), before == null ? null : before.present()));
		rows.add(compared("Late", now.late(), before == null ? null : before.late()));
		rows.add(compared("Half day", now.half(), before == null ? null : before.half()));
		rows.add(compared("Absent", now.absent(), before == null ? null : before.absent()));
		rows.add(compared("Leave", now.leave(), before == null ? null : before.leave()));
		rows.add(compared("Attendance %", now.percent(), before == null ? null : before.percent()));
		return new Section("Attendance", "Previous period: " + p.previousLabel(), COMPARED, rows);
	}

	// ---------------------------------------------------------------- student

	/** One student's period (FR 15.2). */
	@Transactional(readOnly = true)
	public ReportDoc student(long studentId, LocalDate from, LocalDate to) {
		Period p = period(from, to);
		Map<String, Object> s = jdbc.queryForList("select name, gr_no from student where id = ?", studentId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Student"));
		List<Long> sections = jdbc.queryForList("""
				select distinct section_id from enrolment where student_id = ? and academic_year_id = ?
				   and effective_from <= ? and (effective_to is null or effective_to >= ?)""", Long.class, studentId, p.year().id(),
				Date.valueOf(p.to()), Date.valueOf(p.from()));
		Set<Long> allowed = allowedSections(p);
		if (sections.isEmpty() || (allowed != null && sections.stream().noneMatch(allowed::contains))) {
			throw ApiException.notFound("Student");
		}
		List<String> labels = sections.stream().map(id -> structure.requireSection(id).label()).sorted().toList();
		List<Section> out = new ArrayList<>();
		out.add(attendanceSection("student_attendance", "student_id", studentId, p));
		out.add(testsSection(studentId, p));
		out.add(subjectsSection(studentId, p));
		Section results = publishedResults(studentId, p);
		if (!results.rows().isEmpty()) {
			out.add(results);
		}
		if (feesAllowed()) {
			out.add(feeSection(studentId));
		}
		return new ReportDoc("STUDENT REPORT", List.of(s.get("name") + " (GR " + s.get("gr_no") + "), " + String.join(", ", labels),
				p.label() + ", Academic Year " + p.year().name()), out);
	}

	private Section testsSection(long studentId, Period p) {
		String rank = "DENSE".equals(settings.text(SettingKey.POSITION_TIE_METHOD)) ? "dense_rank()" : "rank()";
		List<List<String>> rows = jdbc.query("""
				select t.test_date, t.name, su.name, r.obtained, r.absent, t.max_marks, r.pos
				from (select m.class_test_id, m.student_id, m.obtained, m.absent,
				             case when m.absent then null else %s over (partition by m.class_test_id, m.absent
				                                                        order by m.obtained desc) end as pos
				      from test_mark m) r
				join class_test t on t.id = r.class_test_id join subject su on su.id = t.subject_id
				where r.student_id = ? and t.test_date between ? and ? order by t.test_date, t.id""".formatted(rank),
				(rs, i) -> {
					boolean absent = rs.getBoolean(5);
					BigDecimal got = rs.getBigDecimal(4);
					BigDecimal max = rs.getBigDecimal(6);
					return row(PdfKit.date(rs.getDate(1).toLocalDate()), rs.getString(2), rs.getString(3),
							absent ? "Absent" : plain(got), plain(max), absent ? null : pct(got, max), rs.getObject(7));
				}, studentId, Date.valueOf(p.from()), Date.valueOf(p.to()));
		return new Section("Tests in this period", null, List.of(text("Date"), text("Test"), text("Subject"), number("Marks"),
				number("Out of"), percent("%"), number("Position")), rows);
	}

	/** Percentage per subject (absent counts as 0, as in overall positions, D-51), compared with the previous period. */
	private Map<String, String> subjectPercents(long studentId, LocalDate from, LocalDate to) {
		Map<String, String> result = new LinkedHashMap<>();
		BigDecimal[] all = { BigDecimal.ZERO, BigDecimal.ZERO };
		jdbc.query("""
				select su.name, sum(coalesce(m.obtained, 0)), sum(t.max_marks)
				from test_mark m join class_test t on t.id = m.class_test_id join subject su on su.id = t.subject_id
				where m.student_id = ? and t.test_date between ? and ? group by su.name order by su.name""", rs -> {
			result.put(rs.getString(1), pct(rs.getBigDecimal(2), rs.getBigDecimal(3)));
			all[0] = all[0].add(rs.getBigDecimal(2));
			all[1] = all[1].add(rs.getBigDecimal(3));
		}, studentId, Date.valueOf(from), Date.valueOf(to));
		if (all[1].signum() > 0) {
			result.put("All subjects", pct(all[0], all[1]));
		}
		return result;
	}

	private Section subjectsSection(long studentId, Period p) {
		Map<String, String> now = subjectPercents(studentId, p.from(), p.to());
		Map<String, String> before = p.hasPrevious() ? subjectPercents(studentId, p.previousFrom(), p.previousTo()) : Map.of();
		List<List<String>> rows = new ArrayList<>();
		for (var e : now.entrySet()) {
			rows.add(compared(e.getKey(), e.getValue(), before.get(e.getKey())));
		}
		return new Section("Marks by subject (%)", "Previous period: " + p.previousLabel(),
				List.of(text("Subject"), percent("This period"), percent("Previous period"), number("Change")), rows);
	}

	private Section publishedResults(long studentId, Period p) {
		List<List<String>> rows = jdbc.query("""
				select e.name, s.total_obtained, s.total_max, s.percent, s.grade, s.position, s.passed
				from result_snapshot s join exam_class ec on ec.id = s.exam_class_id and ec.published_version = s.version
				join exam e on e.id = ec.exam_id
				where s.student_id = ? and e.academic_year_id = ? and s.published_at >= ? and s.published_at < ? order by s.published_at""",
				(rs, i) -> row(rs.getString(1), plain(rs.getBigDecimal(2)), plain(rs.getBigDecimal(3)), plain(rs.getBigDecimal(4)),
						rs.getString(5), rs.getObject(6), rs.getObject(7) == null ? null : rs.getBoolean(7) ? "Pass" : "Fail"),
				studentId, p.year().id(), Date.valueOf(p.from()), Date.valueOf(p.to().plusDays(1)));
		return new Section("Published exam results", null, List.of(text("Exam"), number("Marks"), number("Out of"), percent("%"),
				text("Grade"), number("Position"), text("Result")), rows);
	}

	private Section feeSection(long studentId) {
		Date today = Date.valueOf(MoneyInput.today(clock));
		List<List<String>> rows = jdbc.query("""
				select coalesce(sum(balance) filter (where balance > 0), 0),
				       coalesce(sum(balance) filter (where balance > 0 and due_date < ?), 0)
				from fee_line_status where student_id = ?""", (rs, i) -> row(MoneyInput.text(rs.getBigDecimal(1)),
				MoneyInput.text(rs.getBigDecimal(2))), today, studentId);
		return new Section("Fee status today", null, List.of(money("Outstanding"), money("Of which overdue")), rows);
	}

	// ------------------------------------------------------- student record

	/**
	 * Everything about one student for the selected academic year, as one file: details, family, class history,
	 * attendance by month, tests, results and (with fee access) fees and payments. Private details (phones, address,
	 * CNIC/B-Form) appear only for users who may see them; teachers only for students of the sections they teach.
	 */
	@Transactional(readOnly = true)
	public ReportDoc studentRecord(long studentId) {
		YearRef year = yearContext.requireSelected();
		Period p = new Period(year, year.startDate(), year.endDate(), null, null);
		Map<String, Object> s = jdbc.queryForList("""
				select s.gr_no, s.name, s.gender, s.dob, s.place_of_birth, s.b_form_no, s.religion, s.nationality, s.phone,
				       s.address, s.admission_date, s.status, s.left_date, s.previous_school, s.study_group,
				       f.family_no, f.father_name, f.father_cnic, f.father_phone, f.father_profession, f.mother_name,
				       f.mother_phone, f.email, f.address as family_address, f.emergency_name, f.emergency_phone
				from student s join family f on f.id = s.family_id where s.id = ?""", studentId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Student"));
		List<Map<String, Object>> history = jdbc.queryForList("""
				select y.name as year_name, e.academic_year_id, e.section_id, e.effective_from, e.effective_to
				from enrolment e join academic_year y on y.id = e.academic_year_id
				where e.student_id = ? order by e.effective_from""", studentId);
		Set<Long> allowed = allowedSections(p);
		if (allowed != null && history.stream().noneMatch(h -> year.id() == ((Number) h.get("academic_year_id")).longValue()
				&& allowed.contains(((Number) h.get("section_id")).longValue()))) {
			throw ApiException.notFound("Student");
		}
		boolean privateOk = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);
		List<Section> out = new ArrayList<>();

		List<List<String>> details = new ArrayList<>();
		details.add(row("GR no.", s.get("gr_no")));
		details.add(row("Name", s.get("name")));
		details.add(row("Gender", "FEMALE".equals(s.get("gender")) ? "Female" : "Male"));
		details.add(row("Date of birth", date(s.get("dob"))));
		details.add(row("Place of birth", s.get("place_of_birth")));
		if (privateOk) {
			details.add(row("B-Form no.", s.get("b_form_no")));
		}
		details.add(row("Religion", s.get("religion")));
		details.add(row("Nationality", s.get("nationality")));
		if (privateOk) {
			details.add(row("Phone", s.get("phone")));
			details.add(row("Address", s.get("address")));
		}
		details.add(row("Admission date", date(s.get("admission_date"))));
		details.add(row("Status", "LEFT".equals(s.get("status")) ? "Left on " + date(s.get("left_date")) : "Studying"));
		details.add(row("Previous school", s.get("previous_school")));
		details.add(row("Study group", s.get("study_group")));
		out.add(new Section("Student details", null, List.of(text("Field"), text("Value")), withValues(details)));

		List<List<String>> family = new ArrayList<>();
		family.add(row("Family no.", s.get("family_no")));
		family.add(row("Father's name", s.get("father_name")));
		family.add(row("Father's profession", s.get("father_profession")));
		family.add(row("Mother's name", s.get("mother_name")));
		if (privateOk) {
			family.add(row("Father's CNIC", s.get("father_cnic")));
			family.add(row("Father's mobile", s.get("father_phone")));
			family.add(row("Mother's mobile", s.get("mother_phone")));
			family.add(row("Email", s.get("email")));
			family.add(row("Home address", s.get("family_address")));
			family.add(row("Emergency contact", join(s.get("emergency_name"), s.get("emergency_phone"))));
		}
		out.add(new Section("Family and contacts", privateOk ? null : "Phone numbers and addresses are hidden for your role.",
				List.of(text("Field"), text("Value")), withValues(family)));

		out.add(new Section("Class history", null, List.of(text("Academic year"), text("Class"), text("From"), text("To")),
				history.stream().map(h -> row(h.get("year_name"),
						structure.findSection(((Number) h.get("section_id")).longValue()).map(SectionRef::label).orElse("-"),
						date(h.get("effective_from")), h.get("effective_to") == null ? "Now" : date(h.get("effective_to")))).toList()));

		List<List<String>> months = new ArrayList<>();
		jdbc.query("""
				select date_trunc('month', att_date)::date, count(*) filter (where status = 'P'), count(*) filter (where status = 'L'),
				       count(*) filter (where status = 'HD'), count(*) filter (where status = 'A'), count(*) filter (where status = 'LV')
				from student_attendance where student_id = ? and att_date between ? and ? group by 1 order by 1""", rs -> {
			Counts c = new Counts(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
			months.add(row(MONTH.format(rs.getDate(1).toLocalDate()), c.present(), c.late(), c.half(), c.absent(), c.leave(),
					c.percent()));
		}, studentId, Date.valueOf(p.from()), Date.valueOf(p.to()));
		if (!months.isEmpty()) {
			Counts all = counts("student_attendance", "student_id = ? and att_date between ? and ?", studentId,
					Date.valueOf(p.from()), Date.valueOf(p.to()));
			months.add(row("Whole year", all.present(), all.late(), all.half(), all.absent(), all.leave(), all.percent()));
		}
		out.add(new Section("Attendance by month", null, List.of(text("Month"), number("Present"), number("Late"),
				number("Half day"), number("Absent"), number("Leave"), percent("%")), months));

		Section tests = testsSection(studentId, p);
		out.add(new Section("Test marks", null, tests.columns(), tests.rows()));
		List<List<String>> subjects = new ArrayList<>();
		subjectPercents(studentId, p.from(), p.to()).forEach((subject, value) -> subjects.add(row(subject, value)));
		out.add(new Section("Marks by subject (%)", "Absent counts as 0.", List.of(text("Subject"), percent("%")), subjects));
		out.add(publishedResults(studentId, p));

		if (feesAllowed()) {
			List<List<String>> fees = new ArrayList<>();
			BigDecimal[] sum = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
			jdbc.query("""
					select bill_no, description, due_date, net + adjusted, paid, balance from fee_line_status
					where student_id = ? and academic_year_id = ? order by issue_date, bill_no, bill_line_id""", rs -> {
				fees.add(row(rs.getString(1), rs.getString(2), PdfKit.date(rs.getDate(3).toLocalDate()),
						MoneyInput.text(rs.getBigDecimal(4)), MoneyInput.text(rs.getBigDecimal(5)), MoneyInput.text(rs.getBigDecimal(6))));
				sum[0] = sum[0].add(rs.getBigDecimal(4));
				sum[1] = sum[1].add(rs.getBigDecimal(5));
				sum[2] = sum[2].add(rs.getBigDecimal(6));
			}, studentId, year.id());
			if (!fees.isEmpty()) {
				fees.add(row("Total", null, null, MoneyInput.text(sum[0]), MoneyInput.text(sum[1]), MoneyInput.text(sum[2])));
			}
			out.add(new Section("Fees this year", null, List.of(text("Bill no."), text("For"), text("Due"), money("Amount"),
					money("Paid"), money("Balance")), fees));
			out.add(new Section("Payments", "Amounts paid towards this student's fees.",
					List.of(text("Date"), text("Receipt no."), money("Amount")), jdbc.query("""
							select p.payment_date, p.receipt_no, sum(pa.amount)
							from payment_allocation pa join payment p on p.id = pa.payment_id join bill_line bl on bl.id = pa.bill_line_id
							join bill b on b.id = bl.bill_id
							where bl.student_id = ? and b.academic_year_id = ? group by p.id, p.payment_date, p.receipt_no
							order by p.payment_date, p.id""", (rs, i) -> row(PdfKit.date(rs.getDate(1).toLocalDate()), rs.getString(2),
							MoneyInput.text(rs.getBigDecimal(3))), studentId, year.id())));
		}
		String current = history.stream().filter(h -> year.id() == ((Number) h.get("academic_year_id")).longValue())
			.reduce((a, b) -> b)
			.flatMap(h -> structure.findSection(((Number) h.get("section_id")).longValue()).map(SectionRef::label))
			.orElse("Not enrolled this year");
		return new ReportDoc("STUDENT RECORD", List.of(s.get("name") + " (GR " + s.get("gr_no") + "), " + current,
				"Academic Year " + year.name() + ", printed on " + PdfKit.date(MoneyInput.today(clock))), out);
	}

	/** "1 student", "2 students". */
	private static String count(long n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	/** Leaves out details the school has not filled in. */
	private static List<List<String>> withValues(List<List<String>> rows) {
		return rows.stream().filter(r -> r.get(1) != null && !r.get(1).isBlank()).toList();
	}

	private static String date(Object value) {
		return value == null ? null : PdfKit.date(((java.sql.Date) value).toLocalDate());
	}

	private static String join(Object a, Object b) {
		String text = ((a == null ? "" : a + " ") + (b == null ? "" : b)).trim();
		return text.isEmpty() ? null : text;
	}

	// ----------------------------------------------------------- class list

	/**
	 * The students of a class or section today (or on the year's last day), one row each, for printing or Excel.
	 * Teachers get only the sections they teach; mobile numbers need private access, fee balances need fee access.
	 */
	@Transactional(readOnly = true)
	public ReportDoc classList(Long classId, Long sectionId) {
		YearRef year = yearContext.requireSelected();
		Period p = new Period(year, year.startDate(), year.endDate(), null, null);
		if ((classId == null) == (sectionId == null)) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose a class or a section");
		}
		List<SectionRef> sections;
		String label;
		if (sectionId != null) {
			SectionRef one = structure.findSection(sectionId).orElseThrow(() -> ApiException.notFound("Section"));
			sections = List.of(one);
			label = one.label();
		}
		else {
			label = structure.findClass(classId).orElseThrow(() -> ApiException.notFound("Class")).name();
			sections = structure.sectionsOfClass(classId);
		}
		Set<Long> allowed = allowedSections(p);
		List<SectionRef> shown = sections.stream().filter(sec -> allowed == null || allowed.contains(sec.id())).toList();
		if (shown.isEmpty()) {
			throw ApiException.notFound("Section");
		}
		if (shown.size() < sections.size()) {
			label += " (the sections you teach)";
		}
		boolean several = shown.size() > 1;
		boolean privateOk = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);
		boolean fees = feesAllowed();
		LocalDate on = lastDay(p);
		Map<Long, String> labels = new LinkedHashMap<>();
		shown.forEach(sec -> labels.put(sec.id(), sec.label()));

		List<Column> columns = new ArrayList<>(List.of(number("#"), text("GR no."), text("Student"), text("Gender"),
				text("Date of birth"), text("Father's name")));
		if (privateOk) {
			columns.add(text("Father's mobile"));
		}
		if (several) {
			columns.add(text("Section"));
		}
		columns.add(text("Admitted"));
		columns.add(percent("Attendance %"));
		if (fees) {
			columns.add(money("Fees outstanding"));
		}
		List<List<String>> rows = new ArrayList<>();
		jdbc.query("""
				select s.gr_no, s.name, s.gender, s.dob, f.father_name, f.father_phone, e.section_id, s.admission_date,
				       count(a.id) filter (where a.status = 'P'), count(a.id) filter (where a.status = 'L'),
				       count(a.id) filter (where a.status = 'HD'), count(a.id) filter (where a.status = 'A'),
				       count(a.id) filter (where a.status = 'LV'),
				       (select coalesce(sum(v.balance), 0) from fee_line_status v where v.student_id = s.id and v.balance > 0)
				from enrolment e join student s on s.id = e.student_id join family f on f.id = s.family_id
				left join student_attendance a on a.student_id = s.id and a.att_date between ? and ?
				where e.section_id = any (?) and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				group by s.id, f.id, e.section_id order by e.section_id, s.name""", rs -> {
			Counts c = new Counts(rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13));
			List<Object> r = new ArrayList<>(List.of(rows.size() + 1, rs.getString(1), rs.getString(2),
					"FEMALE".equals(rs.getString(3)) ? "Female" : "Male"));
			r.add(rs.getDate(4) == null ? null : PdfKit.date(rs.getDate(4).toLocalDate()));
			r.add(rs.getString(5));
			if (privateOk) {
				r.add(rs.getString(6));
			}
			if (several) {
				r.add(labels.get(rs.getLong(7)));
			}
			r.add(PdfKit.date(rs.getDate(8).toLocalDate()));
			r.add(c.marked() == 0 ? null : c.percent());
			if (fees) {
				r.add(MoneyInput.text(rs.getBigDecimal(14)));
			}
			rows.add(row(r.toArray()));
		}, Date.valueOf(p.from()), Date.valueOf(on), ids(List.copyOf(labels.keySet())), Date.valueOf(on), Date.valueOf(on));
		long girls = rows.stream().filter(r -> "Female".equals(r.get(3))).count();
		long boys = rows.size() - girls;
		String note = count(rows.size(), "student") + " (" + count(boys, "boy") + ", " + count(girls, "girl") + ") on " + PdfKit.date(on)
				+ ". Attendance is for this academic year so far.";
		return new ReportDoc("CLASS LIST", List.of(label + (several ? " (" + shown.size() + " sections)" : ""),
				"Academic Year " + year.name()), List.of(new Section("Students", note, columns, rows)));
	}

	// ---------------------------------------------------------------- teacher

	/** One teacher's period from the records the system keeps (FR 15.3). */
	@Transactional(readOnly = true)
	public ReportDoc teacher(long staffId, LocalDate from, LocalDate to) {
		Period p = period(from, to);
		Map<String, Object> t = jdbc.queryForList("select name, designation from staff where id = ?", staffId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Staff member"));
		List<Section> out = new ArrayList<>();
		out.add(attendanceSection("staff_attendance", "staff_id", staffId, p));
		record Assignment(long sectionId, Long subjectId, String subject, LocalDate from, LocalDate to) {
		}
		List<Assignment> assignments = jdbc.query("""
				select a.section_id, a.subject_id, su.name, a.effective_from, a.effective_to
				from teaching_assignment a left join subject su on su.id = a.subject_id
				where a.staff_id = ? and a.academic_year_id = ? and a.effective_from <= ?
				  and (a.effective_to is null or a.effective_to >= ?) order by a.section_id, su.name""",
				(rs, i) -> new Assignment(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getDate(4).toLocalDate(),
						rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate()),
				staffId, p.year().id(), Date.valueOf(p.to()), Date.valueOf(p.from()));
		out.add(new Section("Classes and subjects", null, List.of(text("Section"), text("Role"), text("From"), text("To")),
				assignments.stream().map(a -> row(structure.requireSection(a.sectionId()).label(),
						a.subjectId() == null ? "Class teacher" : a.subject(), PdfKit.date(a.from()),
						a.to() == null ? null : PdfKit.date(a.to()))).toList()));

		// Marks entry: tests in the teacher's section and subject while assigned
		int dueDays = settings.integer(SettingKey.MARKS_DUE_DAYS);
		List<List<String>> tests = new ArrayList<>();
		int[] onTime = { 0 };
		for (Assignment a : assignments) {
			if (a.subjectId() == null) {
				continue;
			}
			LocalDate start = a.from().isAfter(p.from()) ? a.from() : p.from();
			LocalDate end = a.to() != null && a.to().isBefore(p.to()) ? a.to() : p.to();
			jdbc.query("""
					select t.test_date, t.name, t.section_id,
					       (select count(*) from test_mark m where m.class_test_id = t.id),
					       (select count(*) from enrolment e where e.section_id = t.section_id and e.effective_from <= t.test_date
					          and (e.effective_to is null or e.effective_to >= t.test_date)),
					       (select max(m.first_entered_at at time zone 'Asia/Karachi')::date from test_mark m where m.class_test_id = t.id)
					from class_test t where t.section_id = ? and t.subject_id = ? and t.test_date between ? and ?
					order by t.test_date, t.id""", rs -> {
				LocalDate date = rs.getDate(1).toLocalDate();
				int entered = rs.getInt(4);
				int students = rs.getInt(5);
				LocalDate last = rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate();
				boolean complete = entered >= students && students > 0;
				boolean timely = complete && last != null && !last.isAfter(date.plusDays(dueDays));
				if (timely) {
					onTime[0]++;
				}
				tests.add(row(PdfKit.date(date), structure.requireSection(rs.getLong(3)).label(), a.subject(), rs.getString(2),
						entered + " of " + students, !complete ? "Not complete" : timely ? "On time" : "Late"));
			}, a.sectionId(), a.subjectId(), Date.valueOf(start), Date.valueOf(end));
		}
		out.add(new Section("Marks entry", onTime[0] + " of " + tests.size() + " tests entered completely within " + dueDays
				+ " days of the test.", List.of(text("Date"), text("Section"), text("Subject"), text("Test"), text("Marks entered"),
						text("Status")), tests));

		// Registers: working days as class teacher, up to today
		List<List<String>> registers = new ArrayList<>();
		LocalDate today = MoneyInput.today(clock);
		for (Assignment a : assignments) {
			if (a.subjectId() != null) {
				continue;
			}
			LocalDate start = a.from().isAfter(p.from()) ? a.from() : p.from();
			LocalDate end = a.to() != null && a.to().isBefore(p.to()) ? a.to() : p.to();
			end = end.isAfter(today) ? today : end;
			List<LocalDate> days = end.isBefore(start) ? List.of() : calendar.workingDays(start, end);
			Integer done = jdbc.queryForObject("""
					select count(*) from register_completion where section_id = ? and att_date between ? and ?""", Integer.class,
					a.sectionId(), Date.valueOf(start), Date.valueOf(end));
			int completed = Math.min(done == null ? 0 : done, days.size());
			registers.add(row(structure.requireSection(a.sectionId()).label(), days.size(), completed, days.size() - completed));
		}
		out.add(new Section("Attendance registers", null, List.of(text("Section"), number("Working days"), number("Completed"),
				number("Missing")), registers));
		return new ReportDoc("TEACHER REPORT", List.of(t.get("name") + ", " + t.get("designation"),
				p.label() + ", Academic Year " + p.year().name()), out);
	}

	// ------------------------------------------------------------------ class

	/** A class or section (FR 15.4). Teachers get only the sections they teach. */
	@Transactional(readOnly = true)
	public ReportDoc classReport(Long classId, Long sectionId, LocalDate from, LocalDate to) {
		Period p = period(from, to);
		if ((classId == null) == (sectionId == null)) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose a class or a section");
		}
		List<SectionRef> sections;
		String label;
		if (sectionId != null) {
			SectionRef s = structure.findSection(sectionId).orElseThrow(() -> ApiException.notFound("Section"));
			sections = List.of(s);
			label = s.label();
		}
		else {
			label = structure.findClass(classId).orElseThrow(() -> ApiException.notFound("Class")).name();
			sections = structure.sectionsOfClass(classId);
		}
		Set<Long> allowed = allowedSections(p);
		List<Long> ids = sections.stream().map(SectionRef::id).filter(id -> allowed == null || allowed.contains(id)).toList();
		if (ids.isEmpty()) {
			throw ApiException.notFound("Section");
		}
		if (ids.size() < sections.size()) {
			label += " (the sections you teach)";
		}
		boolean several = ids.size() > 1;
		Array array = ids(ids);
		List<List<String>> summary = new ArrayList<>();
		summary.add(compared("Students at the end of the period", studentsOn(array, p.to()),
				p.hasPrevious() ? studentsOn(array, p.previousTo()) : null));
		summary.add(compared("Joined or moved in", moves(array, "effective_from", p.from(), p.to()),
				p.hasPrevious() ? moves(array, "effective_from", p.previousFrom(), p.previousTo()) : null));
		summary.add(compared("Left or moved out", moves(array, "effective_to", p.from(), p.to()),
				p.hasPrevious() ? moves(array, "effective_to", p.previousFrom(), p.previousTo()) : null));
		summary.add(compared("Average attendance %", sectionPercent(array, p.from(), p.to()),
				p.hasPrevious() ? sectionPercent(array, p.previousFrom(), p.previousTo()) : null));
		summary.add(compared("Registers completed %", registersPercent(array, ids.size(), p.from(), p.to()),
				p.hasPrevious() ? registersPercent(array, ids.size(), p.previousFrom(), p.previousTo()) : null));
		summary.add(compared("Average test %", testAverage(array, p.from(), p.to()),
				p.hasPrevious() ? testAverage(array, p.previousFrom(), p.previousTo()) : null));
		if (feesAllowed()) {
			summary.add(row("Fees outstanding today", jdbc.queryForObject("""
					select coalesce(sum(v.balance), 0) from fee_line_status v where v.balance > 0 and v.student_id in (
					  select e.student_id from enrolment e where e.section_id = any (?) and e.effective_from <= ?
					     and (e.effective_to is null or e.effective_to >= ?))""", BigDecimal.class, array, Date.valueOf(p.to()),
					Date.valueOf(p.to())).toPlainString(), null, null));
		}
		List<Section> out = new ArrayList<>();
		out.add(new Section("Summary", "Previous period: " + p.previousLabel(), COMPARED, summary));
		out.add(new Section("Daily attendance", null, List.of(text("Date"), number("Present"), number("Late"), number("Half day"),
				number("Absent"), number("Leave"), percent("%")), jdbc.query("""
						select att_date, count(*) filter (where status = 'P'), count(*) filter (where status = 'L'),
						       count(*) filter (where status = 'HD'), count(*) filter (where status = 'A'),
						       count(*) filter (where status = 'LV')
						from student_attendance where section_id = any (?) and att_date between ? and ?
						group by att_date order by att_date""", (rs, i) -> {
					Counts c = new Counts(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
					return row(PdfKit.date(rs.getDate(1).toLocalDate()), c.present(), c.late(), c.half(), c.absent(), c.leave(), c.percent());
				}, array, Date.valueOf(p.from()), Date.valueOf(p.to()))));
		out.add(new Section("Test averages", "Absent counts as 0.", List.of(text("Subject"), number("Tests"), percent("Average %")),
				jdbc.query("""
						select su.name, count(distinct t.id), sum(coalesce(m.obtained, 0)), sum(t.max_marks)
						from class_test t join subject su on su.id = t.subject_id join test_mark m on m.class_test_id = t.id
						where t.section_id = any (?) and t.test_date between ? and ? group by su.name order by su.name""",
						(rs, i) -> row(rs.getString(1), rs.getInt(2), pct(rs.getBigDecimal(3), rs.getBigDecimal(4))), array,
						Date.valueOf(p.from()), Date.valueOf(p.to()))));
		out.add(gradeDistribution(array, p));
		List<RepeatedStudent> absent = repeated.repeatedStudents(lastDay(p)).stream().filter(r -> ids.contains(r.sectionId())).toList();
		out.add(new Section("Students with repeated absences", "As of " + PdfKit.date(lastDay(p)) + ".",
				List.of(text("Student"), text("GR no."), text("Section"), number("Days in a row"), text("Since")),
				absent.stream().map(r -> row(r.name(), r.grNo(), r.sectionLabel(), r.daysInARow(), PdfKit.date(r.since()))).toList()));
		return new ReportDoc("CLASS REPORT", List.of(label + (several ? " (" + ids.size() + " sections)" : ""),
				p.label() + ", Academic Year " + p.year().name()), out);
	}

	private Integer studentsOn(Array sections, LocalDate on) {
		return jdbc.queryForObject("""
				select count(distinct student_id) from enrolment where section_id = any (?) and effective_from <= ?
				   and (effective_to is null or effective_to >= ?)""", Integer.class, sections, Date.valueOf(on), Date.valueOf(on));
	}

	private Integer moves(Array sections, String column, LocalDate from, LocalDate to) {
		return jdbc.queryForObject("select count(*) from enrolment where section_id = any (?) and " + column + " between ? and ?",
				Integer.class, sections, Date.valueOf(from), Date.valueOf(to));
	}

	private String sectionPercent(Array sections, LocalDate from, LocalDate to) {
		return counts("student_attendance", "section_id = any (?) and att_date between ? and ?", sections, Date.valueOf(from),
				Date.valueOf(to)).percent();
	}

	/** Registers taken out of registers due: working days up to today × sections. */
	private String registersPercent(Array sections, int sectionCount, LocalDate from, LocalDate to) {
		LocalDate today = MoneyInput.today(clock);
		LocalDate end = to.isAfter(today) ? today : to;
		int due = end.isBefore(from) ? 0 : calendar.workingDays(from, end).size() * sectionCount;
		if (due == 0) {
			return null;
		}
		Integer done = jdbc.queryForObject("select count(*) from register_completion where section_id = any (?) and att_date between ? and ?",
				Integer.class, sections, Date.valueOf(from), Date.valueOf(end));
		return pct(BigDecimal.valueOf(Math.min(done == null ? 0 : done, due)), BigDecimal.valueOf(due));
	}

	private String testAverage(Array sections, LocalDate from, LocalDate to) {
		return jdbc.query("""
				select sum(coalesce(m.obtained, 0)), sum(t.max_marks) from class_test t join test_mark m on m.class_test_id = t.id
				where t.section_id = any (?) and t.test_date between ? and ?""",
				(rs, i) -> rs.getBigDecimal(2) == null ? null : pct(rs.getBigDecimal(1), rs.getBigDecimal(2)), sections,
				Date.valueOf(from), Date.valueOf(to)).getFirst();
	}

	/** How many students reached each grade of the default scale, on their percentage across the period's tests. */
	private Section gradeDistribution(Array sections, Period p) {
		record Band(String grade, BigDecimal min) {
		}
		List<Band> bands = jdbc.query("""
				select b.grade, b.min_percent from grade_band b join grade_scale s on s.id = b.scale_id
				where s.kind = 'MARKS' and s.is_default order by b.display_order""",
				(rs, i) -> new Band(rs.getString(1), rs.getBigDecimal(2)));
		Map<String, Integer> counts = new LinkedHashMap<>();
		bands.forEach(b -> counts.put(b.grade(), 0));
		jdbc.query("""
				select sum(coalesce(m.obtained, 0)), sum(t.max_marks) from class_test t join test_mark m on m.class_test_id = t.id
				where t.section_id = any (?) and t.test_date between ? and ? group by m.student_id""", rs -> {
			BigDecimal percent = new BigDecimal(pct(rs.getBigDecimal(1), rs.getBigDecimal(2)));
			for (Band b : bands) {
				if (b.min() != null && percent.compareTo(b.min()) >= 0) {
					counts.merge(b.grade(), 1, Integer::sum);
					break;
				}
			}
		}, sections, Date.valueOf(p.from()), Date.valueOf(p.to()));
		return new Section("Grade distribution", "Each student's percentage across the tests in this period.",
				List.of(text("Grade"), number("Students")),
				counts.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> row(e.getKey(), e.getValue())).toList());
	}

	private static String pct(BigDecimal got, BigDecimal max) {
		return got.multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP).toPlainString();
	}

	private static String plain(BigDecimal v) {
		return v == null ? null : v.stripTrailingZeros().toPlainString();
	}
}
