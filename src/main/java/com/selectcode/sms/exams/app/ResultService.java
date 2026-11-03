package com.selectcode.sms.exams.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.exams.app.ExamSetupService.BandView;
import com.selectcode.sms.exams.app.ExamSetupService.ExamClassView;
import com.selectcode.sms.exams.app.ExamSetupService.PaperView;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.attendance.AttendancePercent;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The grading engine and the publishing workflow (FR 14.7–14.11, DECISIONS D-14).
 *
 * <p>Percentage = Σ obtained ÷ Σ maximum of papers not exempted × 100, rounded HALF_UP to 2 dp; the grade is the first
 * band whose lowest percentage is ≤ the percentage (lower bound inclusive: 95.00 is A++). A paper is failed when below
 * its passing marks or absent; the student passes when every paper and the overall grade pass. Positions (if the school
 * shows them) rank complete results by percentage: STANDARD 1, 1, 3 or DENSE 1, 1, 2 for ties.
 *
 * <p>Draft → Reviewed → Published per class. Publishing needs every mark entered and freezes one snapshot per student;
 * a correction unlocks the class with a reason and publishing again adds a new version, keeping the old one.
 */
@Service
public class ResultService {

	public record PaperResult(long examSubjectId, long subjectId, String subject, String maxMarks, String passingMarks,
			String obtained, String grade, String flag, String percent, Boolean passed, String highest) {
	}

	public record StudentResult(long studentId, String grNo, String name, String rollNo, Long sectionId,
			String section, List<PaperResult> papers, String totalObtained, String totalMax, String percent,
			String grade, String gradeRemark, Boolean passed, Integer position, int missing, String teacherRemark,
			String principalRemark, String attendancePercent) {
	}

	public record ClassResults(long examClassId, String examName, String className, String gradingMode, String status,
			int version, boolean live, Instant publishedAt, List<StudentResult> students) {
	}

	public record RemarkInput(long studentId, String teacherRemark, String principalRemark) {
	}

	public record CumulativeRow(long studentId, String grNo, String name, String section, Map<String, String> byExam,
			String percent, String grade) {
	}

	public record SubjectAnalysis(String subject, int students, int passed, String passPercent, String average,
			String highest, String lowest) {
	}

	public record Analysis(List<SubjectAnalysis> subjects, Map<String, Integer> grades, int students, int passed) {
	}

	public record VersionRow(int version, String publishedBy, Instant publishedAt, int students) {
	}

	public record StudentExamRow(long examClassId, String examName, String year, String className, int version,
			Instant publishedAt, String totalObtained, String totalMax, String percent, String grade, Integer position,
			Boolean passed) {
	}

	private final JdbcTemplate jdbc;

	private final ExamSetupService setup;

	private final MarksService marks;

	private final AcademicStructure structure;

	private final SettingsService settings;

	private final ObjectProvider<AttendancePercent> attendance;

	private final SchoolUserAdministration users;

	private final AuditService audit;

	private final JsonMapper json;

	private final Clock clock;

	ResultService(JdbcTemplate jdbc, ExamSetupService setup, MarksService marks, AcademicStructure structure,
			SettingsService settings, ObjectProvider<AttendancePercent> attendance, SchoolUserAdministration users,
			AuditService audit, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.setup = setup;
		this.marks = marks;
		this.structure = structure;
		this.settings = settings;
		this.attendance = attendance;
		this.users = users;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	// -------------------------------------------------------------- grading

	/** The grade for a percentage: first band (highest first) whose lower bound it reaches. */
	public static BandView band(List<BandView> bands, BigDecimal percent) {
		for (BandView b : bands) {
			if (b.minPercent() != null && percent.compareTo(new BigDecimal(b.minPercent())) >= 0) {
				return b;
			}
		}
		return bands.getLast();
	}

	public static BigDecimal percent(BigDecimal obtained, BigDecimal max) {
		return obtained.multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP);
	}

	/** Ranks percentages (highest first); STANDARD gives 1, 1, 3 and DENSE 1, 1, 2 for ties. Null = not ranked. */
	public static List<Integer> rank(List<BigDecimal> percents, String method) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < percents.size(); i++) {
			order.add(i);
		}
		order.sort(Comparator.comparing((Integer i) -> percents.get(i), Comparator.nullsLast(Comparator.reverseOrder())));
		Integer[] positions = new Integer[percents.size()];
		int dense = 0;
		BigDecimal previous = null;
		int previousPosition = 0;
		for (int k = 0; k < order.size(); k++) {
			int i = order.get(k);
			BigDecimal p = percents.get(i);
			if (p == null) {
				continue;
			}
			if (previous != null && p.compareTo(previous) == 0) {
				positions[i] = previousPosition;
			}
			else {
				dense++;
				previousPosition = "DENSE".equals(method) ? dense : k + 1;
				positions[i] = previousPosition;
			}
			previous = p;
		}
		return java.util.Arrays.asList(positions);
	}

	/** Works out the results of a class from the current marks (not saved). */
	@Transactional(readOnly = true)
	public List<StudentResult> calculate(long examClassId) {
		ExamClassView ec = setup.examClass(examClassId);
		boolean gradeOnly = "GRADES".equals(ec.gradingMode());
		List<BandView> bands = setup.bands(ec.gradeScaleId());
		Map<String, BandView> byGrade = new HashMap<>();
		bands.forEach(b -> byGrade.put(b.grade(), b));
		Map<Long, Map<Long, Object[]>> markMap = new HashMap<>();
		Map<Long, BigDecimal> highest = new HashMap<>();
		for (PaperView p : ec.papers()) {
			jdbc.query("select student_id, obtained, grade, flag from mark where exam_subject_id = ?", rs -> {
				markMap.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(p.id(),
						new Object[] { rs.getBigDecimal(2), rs.getString(3), rs.getString(4) });
			}, p.id());
			highest.put(p.id(), jdbc.queryForObject("select max(obtained) from mark where exam_subject_id = ?",
					BigDecimal.class, p.id()));
		}
		Map<Long, String[]> remarks = new HashMap<>();
		jdbc.query("select student_id, teacher_remark, principal_remark from result_remark where exam_class_id = ?",
				rs -> { remarks.put(rs.getLong(1), new String[] { rs.getString(2), rs.getString(3) }); }, examClassId);
		LocalDate ref = marks.referenceDate(examClassId);
		LocalDate yearStart = jdbc.queryForObject("""
				select y.start_date from exam_class ec join exam e on e.id = ec.exam_id
				join academic_year y on y.id = e.academic_year_id where ec.id = ?""", Date.class, examClassId).toLocalDate();
		boolean withAttendance = settings.bool(SettingKey.SHOW_ATTENDANCE_ON_REPORT_CARD);
		AttendancePercent att = attendance.getIfAvailable();
		List<StudentResult> results = new ArrayList<>();
		for (long[] s : marks.students(examClassId, null)) {
			long studentId = s[0];
			var info = jdbc.queryForMap("""
					select st.gr_no, st.name, (select e.roll_no from enrolment e where e.student_id = st.id
					   and e.section_id = ? order by e.effective_from desc limit 1) as roll_no
					from student st where st.id = ?""", s[1], studentId);
			Map<Long, Object[]> mine = markMap.getOrDefault(studentId, Map.of());
			List<PaperResult> papers = new ArrayList<>();
			BigDecimal obtained = BigDecimal.ZERO;
			BigDecimal max = BigDecimal.ZERO;
			int missing = 0;
			boolean allPassed = true;
			for (PaperView p : ec.papers()) {
				Object[] m = mine.get(p.id());
				BigDecimal paperMax = new BigDecimal(p.maxMarks());
				BigDecimal passing = p.passingMarks() == null ? null : new BigDecimal(p.passingMarks());
				if (m == null) {
					missing++;
					papers.add(new PaperResult(p.id(), p.subjectId(), p.subjectName(), p.maxMarks(), p.passingMarks(), null,
							null, null, null, null, text(highest.get(p.id()))));
					continue;
				}
				String flag = (String) m[2];
				if ("EXEMPT".equals(flag)) {
					papers.add(new PaperResult(p.id(), p.subjectId(), p.subjectName(), p.maxMarks(), p.passingMarks(), null,
							null, flag, null, null, text(highest.get(p.id()))));
					continue;
				}
				if (gradeOnly) {
					String g = (String) m[1];
					Boolean passed = flag != null ? Boolean.FALSE : byGrade.containsKey(g) ? byGrade.get(g).passing() : null;
					allPassed &= !Boolean.FALSE.equals(passed);
					papers.add(new PaperResult(p.id(), p.subjectId(), p.subjectName(), p.maxMarks(), p.passingMarks(), null, g,
							flag, null, passed, null));
					continue;
				}
				BigDecimal got = "ABSENT".equals(flag) ? BigDecimal.ZERO : (BigDecimal) m[0];
				obtained = obtained.add(got);
				max = max.add(paperMax);
				BigDecimal pct = percent(got, paperMax);
				Boolean passed = "ABSENT".equals(flag) ? Boolean.FALSE : passing == null ? null : got.compareTo(passing) >= 0;
				allPassed &= !Boolean.FALSE.equals(passed);
				papers.add(new PaperResult(p.id(), p.subjectId(), p.subjectName(), p.maxMarks(), p.passingMarks(),
						flag != null ? null : text(got), band(bands, pct).grade(), flag, pct.toPlainString(), passed,
						text(highest.get(p.id()))));
			}
			String pct = null;
			String grade = null;
			String remark = null;
			Boolean passed = null;
			if (!gradeOnly && max.signum() > 0) {
				BigDecimal p = percent(obtained, max);
				BandView b = band(bands, p);
				pct = p.toPlainString();
				grade = b.grade();
				remark = b.remark();
				passed = allPassed && b.passing();
			}
			else if (gradeOnly) {
				passed = allPassed;
			}
			String[] r = remarks.getOrDefault(studentId, new String[2]);
			String attPct = withAttendance && att != null ? att.percent(studentId, yearStart, ref).orElse(null) : null;
			results.add(new StudentResult(studentId, (String) info.get("gr_no"), (String) info.get("name"),
					(String) info.get("roll_no"), s[1],
					structure.findSection(s[1]).map(AcademicStructure.SectionRef::label).orElse(null), papers,
					gradeOnly ? null : text(obtained), gradeOnly ? null : text(max), pct, grade, remark, passed, null,
					missing, r[0], r[1], attPct));
		}
		// Positions over complete results of the whole class
		if (!gradeOnly && settings.bool(SettingKey.SHOW_CLASS_POSITIONS)) {
			List<BigDecimal> percents = results.stream()
				.map(x -> x.missing() == 0 && x.percent() != null ? new BigDecimal(x.percent()) : null)
				.toList();
			List<Integer> positions = rank(percents, settings.text(SettingKey.POSITION_TIE_METHOD));
			for (int i = 0; i < results.size(); i++) {
				StudentResult x = results.get(i);
				results.set(i, new StudentResult(x.studentId(), x.grNo(), x.name(), x.rollNo(), x.sectionId(), x.section(),
						x.papers(), x.totalObtained(), x.totalMax(), x.percent(), x.grade(), x.gradeRemark(), x.passed(),
						positions.get(i), x.missing(), x.teacherRemark(), x.principalRemark(), x.attendancePercent()));
			}
		}
		results.sort(Comparator.comparing((StudentResult x) -> x.section() == null ? "" : x.section())
			.thenComparing(StudentResult::name));
		return results;
	}

	private static String text(BigDecimal v) {
		return v == null ? null : v.stripTrailingZeros().toPlainString();
	}

	// ------------------------------------------------------------ workflow

	@Transactional
	public ExamClassView review(long examClassId, boolean reviewed) {
		Map<String, Object> ec = lock(examClassId);
		String status = (String) ec.get("status");
		if (reviewed && !"DRAFT".equals(status)) {
			throw ApiException.conflict("NOT_DRAFT", "Only a draft can be marked as reviewed");
		}
		if (!reviewed && !"REVIEWED".equals(status)) {
			throw ApiException.conflict("NOT_REVIEWED", "Only a reviewed class can go back to draft");
		}
		jdbc.update("update exam_class set status = ?, reviewed_by = ?, reviewed_at = ? where id = ?",
				reviewed ? "REVIEWED" : "DRAFT", reviewed ? CurrentActor.require().displayName() : null,
				reviewed ? Timestamp.from(clock.instant()) : null, examClassId);
		audit.record(reviewed ? "RESULTS_REVIEWED" : "RESULTS_BACK_TO_DRAFT", "exam_class", examClassId, null,
				Map.of("status", reviewed ? "REVIEWED" : "DRAFT"));
		return setup.examClass(examClassId);
	}

	/** Publishes a reviewed class: every mark must be entered; one frozen snapshot per student (new version). */
	@Transactional
	public ExamClassView publish(long examClassId) {
		Map<String, Object> ec = lock(examClassId);
		if (!"REVIEWED".equals(ec.get("status"))) {
			throw ApiException.conflict("NOT_REVIEWED", "Results must be reviewed before they are published");
		}
		List<StudentResult> results = calculate(examClassId);
		if (results.isEmpty()) {
			throw ApiException.businessRule("NO_STUDENTS", "There are no students in this class");
		}
		int missing = results.stream().mapToInt(StudentResult::missing).sum();
		if (missing > 0) {
			throw ApiException.businessRule("MARKS_MISSING",
					missing + " marks are not entered yet. Enter them, or mark the students Absent or Exempt.");
		}
		int version = ((Number) ec.get("published_version")).intValue() + 1;
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		Timestamp now = Timestamp.from(clock.instant());
		for (StudentResult r : results) {
			jdbc.update("""
					insert into result_snapshot (school_id, exam_class_id, student_id, version, section_id, total_obtained,
					                             total_max, percent, grade, position, passed, data, published_by, published_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", schoolId, examClassId, r.studentId(), version,
					r.sectionId(), r.totalObtained() == null ? null : new BigDecimal(r.totalObtained()),
					r.totalMax() == null ? null : new BigDecimal(r.totalMax()),
					r.percent() == null ? null : new BigDecimal(r.percent()), r.grade(), r.position(), r.passed(),
					json.writeValueAsString(r), actor, now);
		}
		jdbc.update("""
				update exam_class set status = 'PUBLISHED', published_by = ?, published_at = ?, published_version = ?,
				       unlock_reason = null where id = ?""", actor, now, version, examClassId);
		audit.record("RESULTS_PUBLISHED", "exam_class", examClassId, null,
				Map.of("version", version, "students", results.size()));
		return setup.examClass(examClassId);
	}

	/** Opens published results for correction (FR 14.11). Publishing again adds a new version; old ones stay. */
	@Transactional
	public ExamClassView unlock(long examClassId, String reason) {
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the correction");
		}
		Map<String, Object> ec = lock(examClassId);
		if (!"PUBLISHED".equals(ec.get("status"))) {
			throw ApiException.conflict("NOT_PUBLISHED", "Only published results can be unlocked");
		}
		jdbc.update("""
				update exam_class set status = 'DRAFT', unlocked_by = ?, unlocked_at = ?, unlock_reason = ?,
				       reviewed_by = null, reviewed_at = null where id = ?""", CurrentActor.require().displayName(),
				Timestamp.from(clock.instant()), why, examClassId);
		audit.record("RESULTS_UNLOCKED", "exam_class", examClassId, Map.of("status", "PUBLISHED"),
				Map.of("status", "DRAFT"), why);
		return setup.examClass(examClassId);
	}

	private Map<String, Object> lock(long examClassId) {
		setup.examClass(examClassId);
		setup.requireOpenYear(marks.yearOf(examClassId));
		return jdbc.queryForMap("select status, published_version from exam_class where id = ? for update", examClassId);
	}

	// --------------------------------------------------------------- reading

	/**
	 * Results of a class: the frozen snapshot (latest or a given version) once published, otherwise a live preview.
	 * Teachers see only their own sections.
	 */
	@Transactional(readOnly = true)
	public ClassResults results(long examClassId, Long sectionId, Integer version) {
		ExamClassView ec = setup.examClass(examClassId);
		int v = version != null ? version : ec.publishedVersion();
		List<StudentResult> students;
		boolean live = false;
		if (v > 0 && ("PUBLISHED".equals(ec.status()) || version != null)) {
			students = jdbc.query("""
					select data::text from result_snapshot where exam_class_id = ? and version = ? order by id""",
					(rs, i) -> json.readValue(rs.getString(1), StudentResult.class), examClassId, v);
		}
		else {
			students = calculate(examClassId);
			live = true;
		}
		var allowed = visibleSections(examClassId);
		students = students.stream()
			.filter(s -> sectionId == null || sectionId.equals(s.sectionId()))
			.filter(s -> allowed == null || allowed.contains(s.sectionId()))
			.toList();
		return new ClassResults(examClassId, ec.examName(), ec.className(), ec.gradingMode(), ec.status(), live ? 0 : v,
				live, live ? null : ec.publishedAt(), students);
	}

	/** Sections a teacher may see results for; null = all. */
	private java.util.Set<Long> visibleSections(long examClassId) {
		if (DataScope.current() == DataScope.ALL) {
			return null;
		}
		var actor = CurrentActor.require();
		Long staffId = users.staffIdOfUser(actor.actorId()).orElse(null);
		if (staffId == null || DataScope.current() == DataScope.SELF) {
			return java.util.Set.of();
		}
		return structure.sectionsTaughtBy(staffId, marks.yearOf(examClassId), marks.referenceDate(examClassId));
	}

	@Transactional(readOnly = true)
	public List<VersionRow> versions(long examClassId) {
		setup.examClass(examClassId);
		return jdbc.query("""
				select version, max(published_by), max(published_at), count(*) from result_snapshot
				where exam_class_id = ? group by version order by version desc""",
				(rs, i) -> new VersionRow(rs.getInt(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)),
				examClassId);
	}

	/** A student's published results, newest first: the latest published version of each exam (FR 14.14). */
	@Transactional(readOnly = true)
	public List<StudentExamRow> studentResults(long studentId) {
		return jdbc.query("""
				select s.exam_class_id, e.name, y.name, c.name, s.version, s.published_at, s.total_obtained, s.total_max,
				       s.percent, s.grade, s.position, s.passed
				from result_snapshot s
				join exam_class ec on ec.id = s.exam_class_id and ec.published_version = s.version
				join exam e on e.id = ec.exam_id
				join academic_year y on y.id = e.academic_year_id
				join school_class c on c.id = ec.class_id
				where s.student_id = ? order by s.published_at desc""", (rs, i) -> new StudentExamRow(rs.getLong(1),
				rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant(),
				text(rs.getBigDecimal(7)), text(rs.getBigDecimal(8)),
				rs.getBigDecimal(9) == null ? null : rs.getBigDecimal(9).toPlainString(), rs.getString(10),
				(Integer) rs.getObject(11), (Boolean) rs.getObject(12)), studentId);
	}

	/** Students who failed, with their failed papers (FR 14.12). */
	@Transactional(readOnly = true)
	public List<StudentResult> failed(long examClassId) {
		return results(examClassId, null, null).students().stream()
			.filter(s -> Boolean.FALSE.equals(s.passed()))
			.toList();
	}

	/** Per-paper averages and pass rates and the grade spread (FR 14.12 analysis). */
	@Transactional(readOnly = true)
	public Analysis analysis(long examClassId) {
		List<StudentResult> students = results(examClassId, null, null).students();
		Map<String, List<BigDecimal>> percents = new LinkedHashMap<>();
		Map<String, int[]> passes = new LinkedHashMap<>();
		Map<String, Integer> grades = new LinkedHashMap<>();
		for (StudentResult s : students) {
			if (s.grade() != null) {
				grades.merge(s.grade(), 1, Integer::sum);
			}
			for (PaperResult p : s.papers()) {
				if ("EXEMPT".equals(p.flag())) {
					continue;
				}
				percents.computeIfAbsent(p.subject(), k -> new ArrayList<>());
				passes.computeIfAbsent(p.subject(), k -> new int[2]);
				if (p.percent() != null) {
					percents.get(p.subject()).add(new BigDecimal(p.percent()));
				}
				if (p.passed() != null || p.grade() != null || "ABSENT".equals(p.flag())) {
					passes.get(p.subject())[0]++;
					if (!Boolean.FALSE.equals(p.passed())) {
						passes.get(p.subject())[1]++;
					}
				}
			}
		}
		List<SubjectAnalysis> rows = new ArrayList<>();
		for (var e : passes.entrySet()) {
			List<BigDecimal> list = percents.getOrDefault(e.getKey(), List.of());
			int[] c = e.getValue();
			rows.add(new SubjectAnalysis(e.getKey(), c[0], c[1],
					c[0] == 0 ? null : BigDecimal.valueOf(c[1] * 100L).divide(BigDecimal.valueOf(c[0]), 1, RoundingMode.HALF_UP).toPlainString(),
					list.isEmpty() ? null : list.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
						.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP).toPlainString(),
					list.isEmpty() ? null : list.stream().max(BigDecimal::compareTo).get().toPlainString(),
					list.isEmpty() ? null : list.stream().min(BigDecimal::compareTo).get().toPlainString()));
		}
		int passed = (int) students.stream().filter(s -> Boolean.TRUE.equals(s.passed())).count();
		return new Analysis(rows, grades, students.size(), passed);
	}

	/**
	 * Cumulative result of a class over the year's published exams that count (QA-EXM-012):
	 * Σ (weight × exam %) ÷ Σ weight of the exams the student has a percentage in, rounded to 2 dp.
	 */
	@Transactional(readOnly = true)
	public List<CumulativeRow> cumulative(long classId, long yearId) {
		List<Object[]> exams = jdbc.query("""
				select ec.id, e.name, e.weight, ec.published_version from exam e
				join exam_class ec on ec.exam_id = e.id and ec.class_id = ?
				where e.academic_year_id = ? and e.counts_in_cumulative and ec.published_version > 0
				order by coalesce(e.start_date, date '9999-12-31'), e.id""",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4) }, classId,
				yearId);
		Map<Long, CumulativeRow> rows = new LinkedHashMap<>();
		Map<Long, BigDecimal[]> sums = new HashMap<>();
		List<BandView> bands = setup.bands(jdbc.queryForObject(
				"select id from grade_scale where kind = 'MARKS' and is_default", Long.class));
		for (Object[] e : exams) {
			BigDecimal weight = (BigDecimal) e[2];
			jdbc.query("""
					select s.student_id, s.percent, st.gr_no, st.name, s.data ->> 'section' from result_snapshot s
					join student st on st.id = s.student_id where s.exam_class_id = ? and s.version = ?""", rs -> {
				long studentId = rs.getLong(1);
				CumulativeRow row = rows.computeIfAbsent(studentId, k -> {
					try {
						return new CumulativeRow(k, rs.getString(3), rs.getString(4), rs.getString(5), new LinkedHashMap<>(),
								null, null);
					}
					catch (java.sql.SQLException ex) {
						throw new IllegalStateException(ex);
					}
				});
				BigDecimal pct = rs.getBigDecimal(2);
				row.byExam().put((String) e[1], pct == null ? null : pct.toPlainString());
				if (pct != null) {
					BigDecimal[] s = sums.computeIfAbsent(studentId, k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
					s[0] = s[0].add(pct.multiply(weight));
					s[1] = s[1].add(weight);
				}
			}, e[0], e[3]);
		}
		List<CumulativeRow> result = new ArrayList<>();
		for (CumulativeRow r : rows.values()) {
			BigDecimal[] s = sums.get(r.studentId());
			String pct = s == null || s[1].signum() == 0 ? null : s[0].divide(s[1], 2, RoundingMode.HALF_UP).toPlainString();
			result.add(new CumulativeRow(r.studentId(), r.grNo(), r.name(), r.section(), r.byExam(), pct,
					pct == null ? null : band(bands, new BigDecimal(pct)).grade()));
		}
		result.sort(Comparator.comparing(CumulativeRow::name));
		return result;
	}

	// --------------------------------------------------------------- remarks

	/** Teacher and overall remarks (FR 14.9); only while the class is not published. */
	@Transactional
	public ClassResults saveRemarks(long examClassId, List<RemarkInput> input, boolean canWritePrincipal) {
		ExamClassView ec = setup.examClass(examClassId);
		setup.requireOpenYear(marks.yearOf(examClassId));
		if ("PUBLISHED".equals(ec.status())) {
			throw ApiException.conflict("RESULT_PUBLISHED", "Unlock the results to change remarks");
		}
		var allowed = visibleSections(examClassId);
		Map<Long, Long> sectionOf = new HashMap<>();
		marks.students(examClassId, null).forEach(s -> sectionOf.put(s[0], s[1]));
		long schoolId = TenantContext.requireSchoolId();
		for (RemarkInput r : input) {
			Long section = sectionOf.get(r.studentId());
			if (section == null || (allowed != null && !allowed.contains(section))) {
				throw ApiException.notFound("Student");
			}
			String teacher = trim(r.teacherRemark());
			String principal = trim(r.principalRemark());
			if (principal != null && !canWritePrincipal) {
				throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_ALLOWED",
						"Only the reviewer writes the overall remark");
			}
			jdbc.update("""
					insert into result_remark (school_id, exam_class_id, student_id, teacher_remark, principal_remark,
					                           updated_by)
					values (?, ?, ?, ?, ?, ?)
					on conflict (school_id, exam_class_id, student_id) do update
					set teacher_remark = excluded.teacher_remark,
					    principal_remark = case when ? then excluded.principal_remark else result_remark.principal_remark end,
					    updated_by = excluded.updated_by, updated_at = now()""", schoolId, examClassId, r.studentId(),
					teacher, principal, CurrentActor.require().displayName(), canWritePrincipal);
		}
		return results(examClassId, null, null);
	}

	private static String trim(String s) {
		String t = PakFormats.trimToNull(s);
		if (t != null && t.length() > 500) {
			throw ApiException.businessRule("REMARK_TOO_LONG", "Keep remarks under 500 characters");
		}
		return t;
	}
}
