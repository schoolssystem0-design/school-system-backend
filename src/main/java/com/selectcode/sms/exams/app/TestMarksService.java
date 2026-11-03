package com.selectcode.sms.exams.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simple test marks (DECISIONS D-51): pick a section, subject, name, date and maximum, type the marks, and positions
 * follow. Positions rank marks within a test, and the percentage over a period for the overall list; ties follow the
 * school's setting (1, 1, 3 or 1, 1, 2). Teachers work only with the subjects and sections they teach.
 */
@Service
public class TestMarksService {

	public record ClassTestInput(long sectionId, long subjectId, String name, LocalDate testDate, String maxMarks) {
	}

	public record ClassTestView(long id, long academicYearId, long sectionId, String sectionLabel, long subjectId,
			String subjectName, String name, LocalDate testDate, String maxMarks, int entered) {
	}

	public record TestMarkRow(long studentId, String grNo, String name, String rollNo, String obtained, boolean absent,
			String percent, Integer position) {
	}

	public record TestSheet(ClassTestView test, boolean canEdit, List<TestMarkRow> rows) {
	}

	public record TestMarkEntry(long studentId, String obtained, boolean absent) {
	}

	public record OverallRow(long studentId, String grNo, String name, int tests, int testsHeld, String totalObtained,
			String totalMax, String percent, Integer position) {
	}

	public record StudentTestRow(long testId, String testName, String subjectName, String sectionLabel, LocalDate testDate,
			String maxMarks, String obtained, boolean absent, Integer position) {
	}

	private final JdbcTemplate jdbc;

	private final AcademicStructure structure;

	private final SchoolUserAdministration users;

	private final ExamSetupService setup;

	private final YearContext yearContext;

	private final SettingsService settings;

	private final AuditService audit;

	private final Clock clock;

	TestMarksService(JdbcTemplate jdbc, AcademicStructure structure, SchoolUserAdministration users, ExamSetupService setup,
			YearContext yearContext, SettingsService settings, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.structure = structure;
		this.users = users;
		this.setup = setup;
		this.yearContext = yearContext;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- access

	private Long staffId() {
		var actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			return null;
		}
		return users.staffIdOfUser(actor.actorId()).orElse(null);
	}

	private boolean maySeeSection(long sectionId, long yearId, LocalDate on) {
		DataScope scope = DataScope.current();
		if (scope == DataScope.ALL) {
			return true;
		}
		Long staff = scope == DataScope.SELF ? null : staffId();
		return staff != null && structure.sectionsTaughtBy(staff, yearId, on).contains(sectionId);
	}

	private boolean mayEnter(long sectionId, long subjectId, long yearId, LocalDate on) {
		DataScope scope = DataScope.current();
		if (scope == DataScope.ALL) {
			return true;
		}
		Long staff = scope == DataScope.SELF ? null : staffId();
		return staff != null && structure.teaches(staff, yearId, sectionId, subjectId, on);
	}

	private static ApiException notYourSubject() {
		return new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SUBJECT",
				"Only the teacher of this subject in this section can enter these marks");
	}

	// ----------------------------------------------------------------- tests

	private static final String TEST_SQL = """
			select t.id, t.academic_year_id, t.section_id, t.subject_id, s.name as subject_name, t.name, t.test_date,
			       t.max_marks, (select count(*) from test_mark m where m.class_test_id = t.id) as entered
			from class_test t join subject s on s.id = t.subject_id
			""";

	private ClassTestView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		long section = rs.getLong("section_id");
		return new ClassTestView(rs.getLong("id"), rs.getLong("academic_year_id"), section,
				structure.requireSection(section).label(), rs.getLong("subject_id"), rs.getString("subject_name"),
				rs.getString("name"), rs.getDate("test_date").toLocalDate(), text(rs.getBigDecimal("max_marks")),
				rs.getInt("entered"));
	}

	private ClassTestView find(long id) {
		return jdbc.query(TEST_SQL + " where t.id = ?", (rs, i) -> view(rs), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Test"));
	}

	/** Tests of a section in the selected year, newest first. */
	@Transactional(readOnly = true)
	public List<ClassTestView> list(long sectionId) {
		YearRef year = yearContext.requireSelected();
		if (!maySeeSection(sectionId, year.id(), asOf(year))) {
			throw ApiException.notFound("Section");
		}
		return jdbc.query(TEST_SQL + " where t.section_id = ? and t.academic_year_id = ? order by t.test_date desc, t.id desc",
				(rs, i) -> view(rs), sectionId, year.id());
	}

	private LocalDate asOf(YearRef year) {
		LocalDate today = MoneyInput.today(clock);
		return today.isBefore(year.startDate()) ? year.startDate() : today.isAfter(year.endDate()) ? year.endDate() : today;
	}

	@Transactional
	public TestSheet create(ClassTestInput in) {
		YearRef year = yearContext.requireWritable();
		structure.findSection(in.sectionId()).orElseThrow(() -> ApiException.notFound("Section"));
		if (jdbc.queryForList("select id from subject where id = ?", Long.class, in.subjectId()).isEmpty()) {
			throw ApiException.notFound("Subject");
		}
		String name = PakFormats.trimToNull(in.name());
		if (name == null || name.length() > 100) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter the test name, e.g. Monthly test");
		}
		LocalDate date = in.testDate();
		if (date.isBefore(year.startDate()) || date.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The test date must be within " + year.name());
		}
		if (date.isAfter(MoneyInput.today(clock))) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "The test date cannot be in the future");
		}
		BigDecimal max = number(in.maxMarks(), "MAX_MARKS_INVALID", "Enter the total marks as a number, e.g. 50");
		if (max.signum() <= 0) {
			throw ApiException.businessRule("MAX_MARKS_INVALID", "Enter the total marks as a number, e.g. 50");
		}
		if (!mayEnter(in.sectionId(), in.subjectId(), year.id(), date)) {
			throw notYourSubject();
		}
		Long id;
		try {
			id = jdbc.queryForObject("""
					insert into class_test (school_id, academic_year_id, section_id, subject_id, name, test_date, max_marks,
					                        created_by)
					values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), year.id(),
					in.sectionId(), in.subjectId(), name, Date.valueOf(date), max, CurrentActor.require().displayName());
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("TEST_EXISTS", "This test is already in the list for that day");
		}
		audit.record("CLASS_TEST_CREATED", "class_test", id, null, Map.of("name", name, "date", date.toString()));
		return sheet(id);
	}

	/** Removes a test created by mistake. A test with marks stays: its marks are a record. */
	@Transactional
	public void delete(long id) {
		ClassTestView test = find(id);
		setup.requireOpenYear(test.academicYearId());
		if (!mayEnter(test.sectionId(), test.subjectId(), test.academicYearId(), test.testDate())) {
			throw notYourSubject();
		}
		if (test.entered() > 0) {
			throw ApiException.conflict("TEST_HAS_MARKS", "This test has marks, so it cannot be deleted");
		}
		jdbc.update("delete from class_test where id = ?", id);
		audit.record("CLASS_TEST_DELETED", "class_test", id, Map.of("name", test.name()), null);
	}

	// ----------------------------------------------------------------- marks

	/** The students of the section on the test day with their marks and positions. */
	@Transactional(readOnly = true)
	public TestSheet sheet(long id) {
		ClassTestView test = find(id);
		if (!maySeeSection(test.sectionId(), test.academicYearId(), test.testDate())) {
			throw ApiException.notFound("Test");
		}
		BigDecimal max = new BigDecimal(test.maxMarks());
		Date on = Date.valueOf(test.testDate());
		List<Object[]> students = jdbc.query("""
				select s.id, s.gr_no, s.name, e.roll_no, m.obtained, coalesce(m.absent, false)
				from enrolment e join student s on s.id = e.student_id
				left join test_mark m on m.student_id = s.id and m.class_test_id = ?
				where e.section_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				order by nullif(regexp_replace(coalesce(e.roll_no, ''), '\\D', '', 'g'), '')::int nulls last, s.name""",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getBigDecimal(5), rs.getBoolean(6) }, id, test.sectionId(), on, on);
		List<BigDecimal> marks = students.stream().map(s -> (BigDecimal) s[4]).toList();
		List<Integer> positions = ResultService.rank(marks, settings.text(SettingKey.POSITION_TIE_METHOD));
		List<TestMarkRow> rows = new ArrayList<>();
		for (int i = 0; i < students.size(); i++) {
			Object[] s = students.get(i);
			BigDecimal got = (BigDecimal) s[4];
			rows.add(new TestMarkRow((long) s[0], (String) s[1], (String) s[2], (String) s[3], text(got), (boolean) s[5],
					got == null ? null : ResultService.percent(got, max).toPlainString(), positions.get(i)));
		}
		return new TestSheet(test, mayEnter(test.sectionId(), test.subjectId(), test.academicYearId(), test.testDate()),
				rows);
	}

	/** Saves marks. A blank box is "not entered yet"; changing or clearing a saved mark needs a reason and is logged. */
	@Transactional
	public TestSheet save(long id, List<TestMarkEntry> entries, String reason) {
		jdbc.queryForList("select id from class_test where id = ? for update", Long.class, id);
		TestSheet sheet = sheet(id);
		ClassTestView test = sheet.test();
		setup.requireOpenYear(test.academicYearId());
		if (!sheet.canEdit()) {
			throw notYourSubject();
		}
		Map<Long, TestMarkRow> before = new HashMap<>();
		sheet.rows().forEach(r -> before.put(r.studentId(), r));
		BigDecimal max = new BigDecimal(test.maxMarks());
		String why = PakFormats.trimToNull(reason);
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		int changes = 0;
		for (TestMarkEntry e : entries) {
			TestMarkRow old = before.get(e.studentId());
			if (old == null) {
				throw ApiException.businessRule("NOT_IN_SECTION", "A student is not in this section");
			}
			BigDecimal obtained = null;
			if (!e.absent() && PakFormats.trimToNull(e.obtained()) != null) {
				obtained = number(e.obtained(), "MARK_INVALID", old.name() + ": enter marks as a number");
				if (obtained.compareTo(max) > 0) {
					throw ApiException.businessRule("MARK_ABOVE_MAX",
							old.name() + ": " + obtained.toPlainString() + " is more than the maximum " + test.maxMarks());
				}
			}
			String oldValue = old.absent() ? "ABSENT" : old.obtained();
			String newValue = e.absent() ? "ABSENT" : text(obtained);
			if (Objects.equals(oldValue, newValue)) {
				continue;
			}
			if (oldValue != null) {
				if (why == null) {
					throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for changing saved marks");
				}
				jdbc.update("""
						insert into test_mark_change (school_id, class_test_id, student_id, old_value, new_value, reason,
						                              changed_by)
						values (?, ?, ?, ?, ?, ?, ?)""", schoolId, id, e.studentId(), oldValue, newValue, why, actor);
			}
			if (newValue == null) {
				jdbc.update("delete from test_mark where class_test_id = ? and student_id = ?", id, e.studentId());
			}
			else {
				jdbc.update("""
						insert into test_mark (school_id, class_test_id, student_id, obtained, absent, entered_by)
						values (?, ?, ?, ?, ?, ?)
						on conflict (school_id, class_test_id, student_id) do update
						set obtained = excluded.obtained, absent = excluded.absent, entered_by = excluded.entered_by,
						    entered_at = now()""", schoolId, id, e.studentId(), obtained, e.absent(), actor);
			}
			changes++;
		}
		if (changes > 0) {
			audit.record("TEST_MARKS_SAVED", "class_test", id, null, Map.of("test", test.name(), "changes", changes), why);
		}
		return sheet(id);
	}

	// --------------------------------------------------------------- reading

	/**
	 * Overall positions of a section over a period: total obtained ÷ total maximum of the tests each student has a mark
	 * in (absent counts as 0), ranked by that percentage. The test count shows who missed tests.
	 */
	@Transactional(readOnly = true)
	public List<OverallRow> overall(long sectionId, LocalDate from, LocalDate to) {
		YearRef year = yearContext.requireSelected();
		LocalDate end = to.isAfter(year.endDate()) ? year.endDate() : to;
		if (!maySeeSection(sectionId, year.id(), end.isBefore(year.startDate()) ? year.startDate() : end)) {
			throw ApiException.notFound("Section");
		}
		Object[] period = { sectionId, year.id(), Date.valueOf(from), Date.valueOf(to) };
		int held = jdbc.queryForObject("""
				select count(*) from class_test where section_id = ? and academic_year_id = ? and test_date between ? and ?""",
				Integer.class, period);
		List<Object[]> rows = jdbc.query("""
				select s.id, s.gr_no, s.name, count(*), sum(coalesce(m.obtained, 0)), sum(t.max_marks)
				from class_test t
				join test_mark m on m.class_test_id = t.id
				join student s on s.id = m.student_id
				where t.section_id = ? and t.academic_year_id = ? and t.test_date between ? and ?
				group by s.id, s.gr_no, s.name order by s.name""", (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), rs.getBigDecimal(6) }, period);
		List<BigDecimal> percents = rows.stream().map(r -> ResultService.percent((BigDecimal) r[4], (BigDecimal) r[5])).toList();
		List<Integer> positions = ResultService.rank(percents, settings.text(SettingKey.POSITION_TIE_METHOD));
		List<OverallRow> result = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Object[] r = rows.get(i);
			result.add(new OverallRow((long) r[0], (String) r[1], (String) r[2], (int) r[3], held, text((BigDecimal) r[4]),
					text((BigDecimal) r[5]), percents.get(i).toPlainString(), positions.get(i)));
		}
		result.sort(java.util.Comparator.comparing(OverallRow::position).thenComparing(OverallRow::name));
		return result;
	}

	/** A student's test marks, newest first. Teachers see only the sections they teach. */
	@Transactional(readOnly = true)
	public List<StudentTestRow> forStudent(long studentId) {
		String ranking = "DENSE".equals(settings.text(SettingKey.POSITION_TIE_METHOD)) ? "dense_rank()" : "rank()";
		List<Object[]> rows = jdbc.query("""
				select t.id, t.name, su.name, t.section_id, t.test_date, t.max_marks, r.obtained, r.absent, r.pos,
				       t.academic_year_id
				from (select m.class_test_id, m.student_id, m.obtained, m.absent,
				             case when m.absent then null else %s over (partition by m.class_test_id, m.absent
				                                                        order by m.obtained desc) end as pos
				      from test_mark m) r
				join class_test t on t.id = r.class_test_id
				join subject su on su.id = t.subject_id
				where r.student_id = ? order by t.test_date desc, t.id desc""".formatted(ranking),
				(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
						rs.getDate(5).toLocalDate(), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBoolean(8),
						rs.getObject(9) == null ? null : rs.getInt(9), rs.getLong(10) }, studentId);
		List<StudentTestRow> result = new ArrayList<>();
		for (Object[] r : rows) {
			long section = (long) r[3];
			LocalDate date = (LocalDate) r[4];
			if (maySeeSection(section, (long) r[9], date)) {
				result.add(new StudentTestRow((long) r[0], (String) r[1], (String) r[2], structure.requireSection(section).label(),
						date, text((BigDecimal) r[5]), text((BigDecimal) r[6]), (boolean) r[7], (Integer) r[8]));
			}
		}
		return result;
	}

	private static BigDecimal number(String value, String code, String message) {
		BigDecimal n;
		try {
			n = new BigDecimal(value == null ? "" : value.trim());
		}
		catch (NumberFormatException e) {
			throw ApiException.businessRule(code, message);
		}
		if (n.signum() < 0 || n.scale() > 2 || n.compareTo(new BigDecimal("9999.99")) > 0) {
			throw ApiException.businessRule(code, message);
		}
		return n;
	}

	private static String text(BigDecimal v) {
		return v == null ? null : v.stripTrailingZeros().toPlainString();
	}
}
