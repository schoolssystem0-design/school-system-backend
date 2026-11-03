package com.selectcode.sms.exams.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.exams.app.ExamSetupService.ExamClassView;
import com.selectcode.sms.exams.app.ExamSetupService.PaperView;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks entry per section and paper (FR 14.5, 14.6). Teachers enter only papers they teach in that section
 * (QA-EXM-003); a mark above the maximum is refused (QA-EXM-002); a blank is "missing", Absent and Exempt are flags.
 * Marks change only while the class is in draft; a change made after the class was first published is logged with the
 * unlock reason (FR 14.11).
 */
@Service
public class MarksService {

	public record MarkRow(long studentId, String grNo, String name, String rollNo, String obtained, String grade,
			String flag) {
	}

	public record MarkGrid(long examClassId, long examSubjectId, String subjectName, long sectionId,
			String sectionLabel, String gradingMode, String maxMarks, String passingMarks, List<String> grades,
			boolean canEdit, String status, List<MarkRow> rows) {
	}

	public record MarkEntry(long studentId, String obtained, String grade, String flag) {
	}

	public record MissingRow(long examSubjectId, String subjectName, long sectionId, String sectionLabel, int students,
			int missing, List<String> names) {
	}

	private final JdbcTemplate jdbc;

	private final ExamSetupService setup;

	private final AcademicStructure structure;

	private final SchoolUserAdministration users;

	private final AuditService audit;

	private final Clock clock;

	MarksService(JdbcTemplate jdbc, ExamSetupService setup, AcademicStructure structure, SchoolUserAdministration users,
			AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.setup = setup;
		this.structure = structure;
		this.users = users;
		this.audit = audit;
		this.clock = clock;
	}

	/** The day the class is taken as at: the exam's last day, or today if earlier, inside the year. */
	LocalDate referenceDate(long examClassId) {
		var row = jdbc.queryForMap("""
				select e.end_date, y.start_date, y.end_date as year_end, e.academic_year_id
				from exam_class ec join exam e on e.id = ec.exam_id join academic_year y on y.id = e.academic_year_id
				where ec.id = ?""", examClassId);
		LocalDate today = MoneyInput.today(clock);
		LocalDate end = row.get("end_date") == null ? today : ((Date) row.get("end_date")).toLocalDate();
		LocalDate ref = end.isAfter(today) ? today : end;
		LocalDate yStart = ((Date) row.get("start_date")).toLocalDate();
		LocalDate yEnd = ((Date) row.get("year_end")).toLocalDate();
		return ref.isBefore(yStart) ? yStart : ref.isAfter(yEnd) ? yEnd : ref;
	}

	long yearOf(long examClassId) {
		return jdbc.queryForObject("""
				select e.academic_year_id from exam_class ec join exam e on e.id = ec.exam_id where ec.id = ?""", Long.class,
				examClassId);
	}

	/** Sections of the class (on the reference day) and the students in each. */
	List<long[]> students(long examClassId, Long sectionId) {
		LocalDate ref = referenceDate(examClassId);
		long classId = jdbc.queryForObject("select class_id from exam_class where id = ?", Long.class, examClassId);
		List<Object> args = new ArrayList<>(List.of(classId, Date.valueOf(ref), Date.valueOf(ref)));
		String filter = "";
		if (sectionId != null) {
			filter = " and e.section_id = ?";
			args.add(sectionId);
		}
		return jdbc.query("""
				select e.student_id, e.section_id from enrolment e
				where e.class_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)""" + filter,
				(rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }, args.toArray());
	}

	private boolean mayEnter(long examClassId, long sectionId, long subjectId) {
		if (DataScope.current() == DataScope.ALL) {
			return true;
		}
		var actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER || DataScope.current() == DataScope.SELF) {
			return false;
		}
		Long staffId = users.staffIdOfUser(actor.actorId()).orElse(null);
		return staffId != null
				&& structure.teaches(staffId, yearOf(examClassId), sectionId, subjectId, referenceDate(examClassId));
	}

	@Transactional(readOnly = true)
	public MarkGrid grid(long examClassId, long sectionId, long examSubjectId) {
		ExamClassView ec = setup.examClass(examClassId);
		PaperView paper = ec.papers().stream().filter(p -> p.id() == examSubjectId).findFirst()
			.orElseThrow(() -> ApiException.notFound("Paper"));
		if (!mayEnter(examClassId, sectionId, paper.subjectId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SUBJECT",
					"Only the teacher of " + paper.subjectName() + " in this section can enter these marks");
		}
		var section = structure.findSection(sectionId).filter(s -> s.classId() == ec.classId())
			.orElseThrow(() -> ApiException.notFound("Section"));
		Map<Long, Object[]> marks = new HashMap<>();
		jdbc.query("select student_id, obtained, grade, flag from mark where exam_subject_id = ?", rs -> {
			marks.put(rs.getLong(1), new Object[] { rs.getBigDecimal(2), rs.getString(3), rs.getString(4) });
		}, examSubjectId);
		LocalDate ref = referenceDate(examClassId);
		List<MarkRow> rows = jdbc.query("""
				select s.id, s.gr_no, s.name, e.roll_no from enrolment e join student s on s.id = e.student_id
				where e.section_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				order by nullif(regexp_replace(coalesce(e.roll_no, ''), '\\D', '', 'g'), '')::int nulls last, s.name""",
				(rs, i) -> {
					Object[] m = marks.get(rs.getLong(1));
					return new MarkRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
							m == null || m[0] == null ? null : ((BigDecimal) m[0]).stripTrailingZeros().toPlainString(),
							m == null ? null : (String) m[1], m == null ? null : (String) m[2]);
				}, sectionId, Date.valueOf(ref), Date.valueOf(ref));
		List<String> grades = jdbc.queryForList("select grade from grade_band where scale_id = ? order by display_order",
				String.class, ec.gradeScaleId());
		return new MarkGrid(examClassId, examSubjectId, paper.subjectName(), sectionId, section.label(), ec.gradingMode(),
				paper.maxMarks(), paper.passingMarks(), grades, "DRAFT".equals(ec.status()) || "REVIEWED".equals(ec.status()),
				ec.status(), rows);
	}

	/** Saves marks for a section and paper. A reviewed class returns to draft (it must be reviewed again). */
	@Transactional
	public MarkGrid save(long examClassId, long sectionId, long examSubjectId, List<MarkEntry> entries) {
		MarkGrid grid = grid(examClassId, sectionId, examSubjectId);
		setup.requireOpenYear(yearOf(examClassId));
		Map<String, Object> ec = jdbc.queryForMap(
				"select status, published_version, unlock_reason from exam_class where id = ? for update", examClassId);
		if ("PUBLISHED".equals(ec.get("status"))) {
			throw ApiException.conflict("RESULT_PUBLISHED",
					"These results are published. Someone allowed to unlock them must do so, with a reason, first.");
		}
		Set<Long> inSection = new java.util.HashSet<>();
		grid.rows().forEach(r -> inSection.add(r.studentId()));
		Map<Long, MarkRow> before = new HashMap<>();
		grid.rows().forEach(r -> before.put(r.studentId(), r));
		boolean gradeOnly = "GRADES".equals(grid.gradingMode());
		BigDecimal max = new BigDecimal(grid.maxMarks());
		boolean published = ((Number) ec.get("published_version")).intValue() > 0;
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		int changes = 0;
		for (MarkEntry e : entries) {
			if (!inSection.contains(e.studentId())) {
				throw ApiException.businessRule("NOT_IN_SECTION", "A student is not in this section");
			}
			String flag = blank(e.flag()) ? null : e.flag().trim();
			BigDecimal obtained = null;
			String grade = null;
			if (flag != null && !Set.of("ABSENT", "EXEMPT").contains(flag)) {
				throw ApiException.businessRule("FLAG_INVALID", "Use Absent or Exempt");
			}
			if (flag == null && gradeOnly && !blank(e.grade())) {
				grade = e.grade().trim();
				if (!grid.grades().contains(grade)) {
					throw ApiException.businessRule("GRADE_INVALID", "Choose one of " + String.join(", ", grid.grades()));
				}
			}
			if (flag == null && !gradeOnly && !blank(e.obtained())) {
				try {
					obtained = new BigDecimal(e.obtained().trim());
				}
				catch (NumberFormatException ex) {
					throw ApiException.businessRule("MARK_INVALID", "Enter marks as a number");
				}
				if (obtained.signum() < 0 || obtained.scale() > 2) {
					throw ApiException.businessRule("MARK_INVALID", "Enter marks as a number");
				}
				if (obtained.compareTo(max) > 0) {
					throw ApiException.businessRule("MARK_ABOVE_MAX",
							before.get(e.studentId()).name() + ": " + obtained.toPlainString() + " is more than the maximum "
									+ grid.maxMarks());
				}
			}
			String newValue = flag != null ? flag : obtained != null ? obtained.stripTrailingZeros().toPlainString() : grade;
			MarkRow old = before.get(e.studentId());
			String oldValue = old.flag() != null ? old.flag() : old.obtained() != null ? old.obtained() : old.grade();
			if (java.util.Objects.equals(oldValue, newValue)) {
				continue;
			}
			if (newValue == null && published) {
				throw ApiException.businessRule("MARK_REQUIRED", old.name()
						+ ": a published mark cannot be removed. Enter the corrected mark, or Absent or Exempt.");
			}
			changes++;
			if (newValue == null) {
				jdbc.update("delete from mark where exam_subject_id = ? and student_id = ?", examSubjectId, e.studentId());
			}
			else {
				jdbc.update("""
						insert into mark (school_id, exam_subject_id, student_id, obtained, grade, flag, entered_by)
						values (?, ?, ?, ?, ?, ?, ?)
						on conflict (school_id, exam_subject_id, student_id) do update
						set obtained = excluded.obtained, grade = excluded.grade, flag = excluded.flag,
						    entered_by = excluded.entered_by, entered_at = now()""", schoolId, examSubjectId,
						e.studentId(), obtained, grade, flag, actor);
			}
			if (published) {
				jdbc.update("""
						insert into mark_change (school_id, exam_subject_id, student_id, old_value, new_value, reason,
						                         changed_by)
						values (?, ?, ?, ?, ?, ?, ?)""", schoolId, examSubjectId, e.studentId(), oldValue, newValue,
						ec.get("unlock_reason") == null ? "Correction after publishing" : ec.get("unlock_reason"), actor);
			}
		}
		if (changes > 0 && "REVIEWED".equals(ec.get("status"))) {
			jdbc.update("update exam_class set status = 'DRAFT', reviewed_by = null, reviewed_at = null where id = ?",
					examClassId);
		}
		if (changes > 0) {
			audit.record("MARKS_SAVED", "exam_class", examClassId, null,
					Map.of("paper", grid.subjectName(), "section", grid.sectionLabel(), "changes", changes));
		}
		return grid(examClassId, sectionId, examSubjectId);
	}

	/** Papers and sections with marks not entered yet (FR 14.6). Absent and Exempt are entries, not missing. */
	@Transactional(readOnly = true)
	public List<MissingRow> missing(long examClassId) {
		ExamClassView ec = setup.examClass(examClassId);
		Map<Long, List<Long>> bySection = new HashMap<>();
		students(examClassId, null).forEach(s -> bySection.computeIfAbsent(s[1], k -> new ArrayList<>()).add(s[0]));
		List<MissingRow> rows = new ArrayList<>();
		for (PaperView p : ec.papers()) {
			Set<Long> marked = Set.copyOf(jdbc.queryForList("select student_id from mark where exam_subject_id = ?",
					Long.class, p.id()));
			for (var section : bySection.entrySet()) {
				List<Long> missing = section.getValue().stream().filter(id -> !marked.contains(id)).toList();
				if (missing.isEmpty()) {
					continue;
				}
				List<String> names = missing.stream().limit(10).map(id -> jdbc.queryForObject(
						"select name from student where id = ?", String.class, id)).toList();
				rows.add(new MissingRow(p.id(), p.subjectName(), section.getKey(),
						structure.findSection(section.getKey()).map(AcademicStructure.SectionRef::label).orElse(""),
						section.getValue().size(), missing.size(), names));
			}
		}
		return rows;
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
