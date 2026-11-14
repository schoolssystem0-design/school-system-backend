package com.selectcode.sms.exams.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.events.SchoolCreatedEvent;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exams and terms (FR 14.1), their classes and papers with maximum and passing marks copied from the class subjects
 * (FR 14.2), the datesheet (FR 14.3) and grade scales (D-14). A hidden exam is not shown to teachers (QA-EXM-001).
 */
@Service
public class ExamSetupService {

	public record ExamInput(String name, LocalDate startDate, LocalDate endDate, String comments,
			Boolean countsInCumulative, String weight, Boolean visible) {
	}

	public record ExamClassRow(long id, long classId, String className, String gradingMode, String status,
			int papers, int publishedVersion, Instant publishedAt) {
	}

	public record ExamView(long id, long academicYearId, String name, LocalDate startDate, LocalDate endDate,
			String comments, boolean countsInCumulative, String weight, boolean visible, long version,
			List<ExamClassRow> classes) {
	}

	public record PaperView(long id, long subjectId, String subjectName, String maxMarks, String passingMarks,
			int displayOrder, LocalDate paperDate, LocalTime startTime, LocalTime endTime, String syllabus) {
	}

	public record PaperInput(long subjectId, String maxMarks, String passingMarks, LocalDate paperDate,
			LocalTime startTime, LocalTime endTime, String syllabus) {
	}

	public record ExamClassView(long id, long examId, String examName, long classId, String className,
			String gradingMode, long gradeScaleId, String status, String reviewedBy, Instant reviewedAt,
			String publishedBy, Instant publishedAt, int publishedVersion, String unlockReason, List<PaperView> papers) {
	}

	public record BandView(long id, String grade, String minPercent, String remark, boolean passing, int displayOrder) {
	}

	public record ScaleView(long id, String name, String kind, boolean isDefault, List<BandView> bands) {
	}

	public record BandInput(String grade, String minPercent, String remark, boolean passing) {
	}

	private final JdbcTemplate jdbc;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final AuditService audit;

	ExamSetupService(JdbcTemplate jdbc, YearContext yearContext, AcademicYearLookup years, AuditService audit) {
		this.jdbc = jdbc;
		this.yearContext = yearContext;
		this.years = years;
		this.audit = audit;
	}

	@EventListener
	public void onSchoolCreated(SchoolCreatedEvent event) {
		seed(jdbc, event.schoolId());
	}

	/** Default "Standard grades" and "Early years" scales (also used by tests that create schools directly). */
	public static void seed(JdbcTemplate jdbc, long schoolId) {
		Long marks = jdbc.queryForObject("""
				insert into grade_scale (school_id, name, kind, is_default) values (?, 'Standard grades', 'MARKS', true)
				returning id""", Long.class, schoolId);
		Object[][] bands = { { "A++", 95, "Outstanding", true }, { "A+", 90, "Excellent", true },
				{ "A", 80, "Very good", true }, { "B", 70, "Good", true }, { "C", 60, "Fair", true },
				{ "D", 50, "Satisfactory", true }, { "E", 40, "Pass", true }, { "F", 0, "Fail", false } };
		for (int i = 0; i < bands.length; i++) {
			jdbc.update("""
					insert into grade_band (school_id, scale_id, grade, min_percent, remark, passing, display_order)
					values (?, ?, ?, ?, ?, ?, ?)""", schoolId, marks, bands[i][0], bands[i][1], bands[i][2], bands[i][3],
					i + 1);
		}
		Long grades = jdbc.queryForObject("""
				insert into grade_scale (school_id, name, kind, is_default) values (?, 'Early years', 'GRADES', true)
				returning id""", Long.class, schoolId);
		String[][] early = { { "A", "Excellent" }, { "B", "Very good" }, { "C", "Good" }, { "D", "Satisfactory" },
				{ "E", "Needs improvement" } };
		for (int i = 0; i < early.length; i++) {
			jdbc.update("""
					insert into grade_band (school_id, scale_id, grade, remark, passing, display_order)
					values (?, ?, ?, ?, true, ?)""", schoolId, grades, early[i][0], early[i][1], i + 1);
		}
	}

	// ----------------------------------------------------------------- scales

	@Transactional(readOnly = true)
	public List<ScaleView> scales() {
		return jdbc.query("select id, name, kind, is_default from grade_scale order by kind, name",
				(rs, i) -> new ScaleView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
						bands(rs.getLong(1))));
	}

	public List<BandView> bands(long scaleId) {
		return jdbc.query("""
				select id, grade, min_percent, remark, passing, display_order from grade_band where scale_id = ?
				order by display_order""", (rs, i) -> new BandView(rs.getLong(1), rs.getString(2),
				rs.getBigDecimal(3) == null ? null : rs.getBigDecimal(3).toPlainString(), rs.getString(4),
				rs.getBoolean(5), rs.getInt(6)), scaleId);
	}

	/**
	 * Replaces the bands of a scale. Marks scales need a band starting at 0 and distinct lower bounds, highest first;
	 * a scale already used by a published result cannot change (published results keep their frozen grades anyway).
	 */
	@Transactional
	public ScaleView saveBands(long scaleId, List<BandInput> input) {
		ScaleView scale = scales().stream().filter(s -> s.id() == scaleId).findFirst()
			.orElseThrow(() -> ApiException.notFound("Grade scale"));
		if (input == null || input.isEmpty() || input.size() > 20) {
			throw ApiException.businessRule("BANDS_INVALID", "Give between 1 and 20 grades");
		}
		List<BigDecimal> mins = new ArrayList<>();
		for (BandInput b : input) {
			if (PakFormats.trimToNull(b.grade()) == null || b.grade().trim().length() > 10) {
				throw ApiException.businessRule("BANDS_INVALID", "Each grade needs a short name (up to 10 characters)");
			}
			if ("MARKS".equals(scale.kind())) {
				try {
					BigDecimal min = new BigDecimal(b.minPercent().trim());
					if (min.signum() < 0 || min.compareTo(BigDecimal.valueOf(100)) > 0 || min.scale() > 2) {
						throw new NumberFormatException();
					}
					mins.add(min);
				}
				catch (RuntimeException e) {
					throw ApiException.businessRule("BANDS_INVALID", "Each grade needs a lowest percentage from 0 to 100");
				}
			}
		}
		if ("MARKS".equals(scale.kind())) {
			for (int i = 1; i < mins.size(); i++) {
				if (mins.get(i).compareTo(mins.get(i - 1)) >= 0) {
					throw ApiException.businessRule("BANDS_ORDER", "List grades from highest to lowest, each lower than the one before");
				}
			}
			if (mins.getLast().signum() != 0) {
				throw ApiException.businessRule("BANDS_ZERO", "The lowest grade must start at 0%");
			}
		}
		jdbc.update("delete from grade_band where scale_id = ?", scaleId);
		long schoolId = TenantContext.requireSchoolId();
		for (int i = 0; i < input.size(); i++) {
			BandInput b = input.get(i);
			jdbc.update("""
					insert into grade_band (school_id, scale_id, grade, min_percent, remark, passing, display_order)
					values (?, ?, ?, ?, ?, ?, ?)""", schoolId, scaleId, b.grade().trim(),
					"MARKS".equals(scale.kind()) ? mins.get(i) : null, PakFormats.trimToNull(b.remark()), b.passing(), i + 1);
		}
		audit.record("GRADE_SCALE_SAVED", "grade_scale", scaleId, scale.bands(), bands(scaleId));
		return scales().stream().filter(s -> s.id() == scaleId).findFirst().orElseThrow();
	}

	// ------------------------------------------------------------------ exams

	/** Exams of the selected year. Teachers (assigned-classes scope) see visible exams only. */
	@Transactional(readOnly = true)
	public List<ExamView> exams() {
		long yearId = yearContext.requireSelected().id();
		boolean onlyVisible = DataScope.current() != DataScope.ALL;
		return jdbc.query("""
				select id, academic_year_id, name, start_date, end_date, comments, counts_in_cumulative, weight, visible, version
				from exam where academic_year_id = ?""" + (onlyVisible ? " and visible" : "")
				+ " order by coalesce(start_date, date '9999-12-31'), id", (rs, i) -> exam(rs), yearId);
	}

	public ExamView exam(long id) {
		boolean onlyVisible = DataScope.current() != DataScope.ALL;
		List<ExamView> rows = jdbc.query("""
				select id, academic_year_id, name, start_date, end_date, comments, counts_in_cumulative, weight, visible, version
				from exam where id = ?""" + (onlyVisible ? " and visible" : ""), (rs, i) -> exam(rs), id);
		if (rows.isEmpty()) {
			throw ApiException.notFound("Exam");
		}
		return rows.getFirst();
	}

	private ExamView exam(java.sql.ResultSet rs) throws java.sql.SQLException {
		long id = rs.getLong(1);
		return new ExamView(id, rs.getLong(2), rs.getString(3),
				rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(),
				rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBoolean(7),
				rs.getBigDecimal(8).stripTrailingZeros().toPlainString(), rs.getBoolean(9), rs.getLong(10), classes(id));
	}

	private List<ExamClassRow> classes(long examId) {
		return jdbc.query("""
				select ec.id, ec.class_id, c.name, c.grading_mode, ec.status,
				       (select count(*) from exam_subject es where es.exam_class_id = ec.id), ec.published_version,
				       ec.published_at
				from exam_class ec join school_class c on c.id = ec.class_id where ec.exam_id = ? order by c.display_order""",
				(rs, i) -> new ExamClassRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getInt(6), rs.getInt(7),
						rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()), examId);
	}

	@Transactional
	public ExamView create(ExamInput in) {
		YearRef year = yearContext.requireWritable();
		Fields f = fields(in, year);
		try {
			Long id = jdbc.queryForObject("""
					insert into exam (school_id, academic_year_id, name, start_date, end_date, comments,
					                  counts_in_cumulative, weight, visible, created_by)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
					year.id(), f.name, f.start, f.end, f.comments, f.cumulative, f.weight, f.visible,
					CurrentActor.require().displayName());
			audit.record("EXAM_CREATED", "exam", id, null, Map.of("name", f.name, "year", year.name()));
			return exam(id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "There is already an exam called " + f.name + " this year");
		}
	}

	@Transactional
	public ExamView update(long id, ExamInput in, long version) {
		ExamView before = exam(id);
		if (before.version() != version) {
			throw ApiException.conflict("CONFLICT_VERSION", "The exam was changed by someone else. Reload and try again.");
		}
		YearRef year = yearContext.requireSelected();
		if (before.academicYearId() != year.id()) {
			throw ApiException.notFound("Exam");
		}
		yearContext.requireWritable(year);
		Fields f = fields(in, year);
		try {
			jdbc.update("""
					update exam set name = ?, start_date = ?, end_date = ?, comments = ?, counts_in_cumulative = ?,
					       weight = ?, visible = ?, version = version + 1 where id = ?""", f.name, f.start, f.end, f.comments,
					f.cumulative, f.weight, f.visible, id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "There is already an exam called " + f.name + " this year");
		}
		ExamView after = exam(id);
		audit.record("EXAM_UPDATED", "exam", id, before, after);
		return after;
	}

	/** Closed years are read-only: checks the exam's own year, whichever year the user has selected. */
	void requireOpenYear(long yearId) {
		yearContext.requireWritable(years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
	}

	long yearOfClass(long examClassId) {
		return jdbc.queryForObject("""
				select e.academic_year_id from exam_class ec join exam e on e.id = ec.exam_id where ec.id = ?""", Long.class,
				examClassId);
	}

	private record Fields(String name, Date start, Date end, String comments, boolean cumulative, BigDecimal weight,
			boolean visible) {
	}

	private static Fields fields(ExamInput in, YearRef year) {
		String name = PakFormats.trimToNull(in.name());
		if (name == null || name.length() > 100) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter the exam name, e.g. First term");
		}
		if (in.startDate() != null && in.endDate() != null && in.endDate().isBefore(in.startDate())) {
			throw ApiException.businessRule("DATES_INVALID", "The last day is before the first day");
		}
		for (LocalDate d : new LocalDate[] { in.startDate(), in.endDate() }) {
			if (d != null && (d.isBefore(year.startDate()) || d.isAfter(year.endDate()))) {
				throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "Exam dates must be within " + year.name());
			}
		}
		BigDecimal weight;
		try {
			weight = in.weight() == null || in.weight().isBlank() ? BigDecimal.ONE : new BigDecimal(in.weight().trim());
		}
		catch (NumberFormatException e) {
			throw ApiException.businessRule("WEIGHT_INVALID", "The weight must be a number such as 1 or 40");
		}
		if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("999.99")) > 0 || weight.scale() > 2) {
			throw ApiException.businessRule("WEIGHT_INVALID", "The weight must be a number such as 1 or 40");
		}
		return new Fields(name, in.startDate() == null ? null : Date.valueOf(in.startDate()),
				in.endDate() == null ? null : Date.valueOf(in.endDate()), PakFormats.trimToNull(in.comments()),
				in.countsInCumulative() == null || in.countsInCumulative(), weight, Boolean.TRUE.equals(in.visible()));
	}

	// ---------------------------------------------------------------- classes

	/** Adds classes to an exam; their papers are copied from the class subjects of the year (FR 14.2). */
	@Transactional
	public ExamView addClasses(long examId, List<Long> classIds) {
		ExamView exam = exam(examId);
		requireOpenYear(exam.academicYearId());
		long schoolId = TenantContext.requireSchoolId();
		for (long classId : classIds) {
			String mode = jdbc.queryForList("select grading_mode from school_class where id = ? and status = 'ACTIVE'",
					String.class, classId).stream().findFirst()
				.orElseThrow(() -> ApiException.notFound("Class"));
			long scale = jdbc.queryForObject("select id from grade_scale where kind = ? and is_default", Long.class,
					"GRADES".equals(mode) ? "GRADES" : "MARKS");
			Long ecId;
			try {
				ecId = jdbc.queryForObject("""
						insert into exam_class (school_id, exam_id, class_id, grade_scale_id) values (?, ?, ?, ?)
						returning id""", Long.class, schoolId, examId, classId, scale);
			}
			catch (DuplicateKeyException e) {
				continue;
			}
			jdbc.update("""
					insert into exam_subject (school_id, exam_class_id, subject_id, max_marks, passing_marks, display_order)
					select school_id, ?, subject_id, max_marks, passing_marks, display_order from class_subject
					where academic_year_id = ? and class_id = ? and status = 'ACTIVE'""", ecId, exam.academicYearId(),
					classId);
		}
		audit.record("EXAM_CLASSES_ADDED", "exam", examId, null, Map.of("classIds", classIds));
		return exam(examId);
	}

	@Transactional(readOnly = true)
	public ExamClassView examClass(long examClassId) {
		List<ExamClassView> rows = jdbc.query("""
				select ec.id, ec.exam_id, e.name, ec.class_id, c.name, c.grading_mode, ec.grade_scale_id, ec.status,
				       ec.reviewed_by, ec.reviewed_at, ec.published_by, ec.published_at, ec.published_version,
				       ec.unlock_reason, e.visible
				from exam_class ec join exam e on e.id = ec.exam_id join school_class c on c.id = ec.class_id
				where ec.id = ?""", (rs, i) -> {
			if (!rs.getBoolean(15) && DataScope.current() != DataScope.ALL) {
				return null;
			}
			return new ExamClassView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
					rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
					rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getString(11),
					rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant(), rs.getInt(13), rs.getString(14),
					papers(rs.getLong(1)));
		}, examClassId);
		if (rows.isEmpty() || rows.getFirst() == null) {
			throw ApiException.notFound("Exam class");
		}
		return rows.getFirst();
	}

	public List<PaperView> papers(long examClassId) {
		return jdbc.query("""
				select es.id, es.subject_id, s.name, es.max_marks, es.passing_marks, es.display_order, es.paper_date,
				       es.start_time, es.end_time, es.syllabus
				from exam_subject es join subject s on s.id = es.subject_id where es.exam_class_id = ?
				order by es.display_order, s.name""", (rs, i) -> new PaperView(rs.getLong(1), rs.getLong(2),
				rs.getString(3), rs.getBigDecimal(4).toPlainString(),
				rs.getBigDecimal(5) == null ? null : rs.getBigDecimal(5).toPlainString(), rs.getInt(6),
				rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(),
				rs.getTime(8) == null ? null : rs.getTime(8).toLocalTime(),
				rs.getTime(9) == null ? null : rs.getTime(9).toLocalTime(), rs.getString(10)), examClassId);
	}

	/**
	 * Saves the papers of a class: subjects, maximum and passing marks and the datesheet. Allowed only while the
	 * class is in draft; papers that already have marks keep their subject and cannot be removed.
	 */
	@Transactional
	public ExamClassView savePapers(long examClassId, List<PaperInput> input) {
		ExamClassView ec = examClass(examClassId);
		requireOpenYear(yearOfClass(examClassId));
		if (!"DRAFT".equals(ec.status())) {
			throw ApiException.conflict("NOT_DRAFT", "Return the class to draft before changing its papers");
		}
		Map<Long, PaperView> existing = new java.util.HashMap<>();
		ec.papers().forEach(p -> existing.put(p.subjectId(), p));
		java.util.Set<Long> keep = new java.util.HashSet<>();
		long schoolId = TenantContext.requireSchoolId();
		int order = 1;
		for (PaperInput p : input) {
			BigDecimal max = number(p.maxMarks(), "Maximum marks", false);
			BigDecimal pass = p.passingMarks() == null || p.passingMarks().isBlank() ? null
					: number(p.passingMarks(), "Passing marks", true);
			if (pass != null && pass.compareTo(max) > 0) {
				throw ApiException.businessRule("PASSING_ABOVE_MAX", "Passing marks cannot be more than the maximum");
			}
			if (p.startTime() != null && p.endTime() != null && !p.endTime().isAfter(p.startTime())) {
				throw ApiException.businessRule("TIMES_INVALID", "The paper ends before it starts");
			}
			if (!keep.add(p.subjectId())) {
				throw ApiException.businessRule("SUBJECT_TWICE", "A subject appears twice");
			}
			PaperView old = existing.get(p.subjectId());
			if (old != null) {
				BigDecimal highest = jdbc.queryForObject("select max(obtained) from mark where exam_subject_id = ?",
						BigDecimal.class, old.id());
				if (highest != null && highest.compareTo(max) > 0) {
					throw ApiException.businessRule("MARKS_ABOVE_MAX",
							old.subjectName() + ": some marks are higher than the new maximum");
				}
				jdbc.update("""
						update exam_subject set max_marks = ?, passing_marks = ?, display_order = ?, paper_date = ?,
						       start_time = ?, end_time = ?, syllabus = ? where id = ?""", max, pass, order,
						p.paperDate() == null ? null : Date.valueOf(p.paperDate()),
						p.startTime() == null ? null : Time.valueOf(p.startTime()),
						p.endTime() == null ? null : Time.valueOf(p.endTime()), PakFormats.trimToNull(p.syllabus()), old.id());
			}
			else {
				jdbc.update("""
						insert into exam_subject (school_id, exam_class_id, subject_id, max_marks, passing_marks,
						                          display_order, paper_date, start_time, end_time, syllabus)
						values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, examClassId, p.subjectId(), max, pass, order,
						p.paperDate() == null ? null : Date.valueOf(p.paperDate()),
						p.startTime() == null ? null : Time.valueOf(p.startTime()),
						p.endTime() == null ? null : Time.valueOf(p.endTime()), PakFormats.trimToNull(p.syllabus()));
			}
			order++;
		}
		for (PaperView old : ec.papers()) {
			if (!keep.contains(old.subjectId())) {
				Long marks = jdbc.queryForObject("select count(*) from mark where exam_subject_id = ?", Long.class,
						old.id());
				if (marks != null && marks > 0) {
					throw ApiException.conflict("PAPER_HAS_MARKS", old.subjectName() + " already has marks and cannot be removed");
				}
				jdbc.update("delete from exam_subject where id = ?", old.id());
			}
		}
		audit.record("EXAM_PAPERS_SAVED", "exam_class", examClassId, ec.papers(), papers(examClassId));
		return examClass(examClassId);
	}

	private static BigDecimal number(String raw, String field, boolean allowZero) {
		try {
			BigDecimal v = new BigDecimal(raw == null ? "" : raw.trim());
			if (v.scale() > 2 || v.signum() < 0 || (!allowZero && v.signum() == 0) || v.compareTo(new BigDecimal("9999")) > 0) {
				throw new NumberFormatException();
			}
			return v;
		}
		catch (NumberFormatException e) {
			throw ApiException.businessRule("NUMBER_INVALID", field + ": enter a number such as 100 or 33");
		}
	}
}
