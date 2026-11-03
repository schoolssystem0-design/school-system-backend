package com.selectcode.sms.academics;

import com.selectcode.sms.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read API of the academics module for other modules (students, admissions, school rollover). */
@Service
public class AcademicStructure {

	public record ClassRef(long id, String name, String shortName, int displayOrder, String gradingMode,
			boolean active) {
	}

	public record SectionRef(long id, long classId, String className, String sectionName, int classOrder,
			boolean active) {

		public String label() {
			return className + " - " + sectionName;
		}
	}

	private final JdbcTemplate jdbc;

	AcademicStructure(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<ClassRef> classesInOrder() {
		return jdbc.query("""
				select id, name, short_name, display_order, grading_mode, status from school_class
				order by display_order, name""", (rs, i) -> new ClassRef(rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getInt(4), rs.getString(5), "ACTIVE".equals(rs.getString(6))));
	}

	@Transactional(readOnly = true)
	public Optional<ClassRef> findClass(long id) {
		return classesInOrder().stream().filter(c -> c.id() == id).findFirst();
	}

	public ClassRef requireClass(long id) {
		return findClass(id).orElseThrow(() -> ApiException.notFound("Class"));
	}

	/** The next class by display order (for promotion), if any. */
	@Transactional(readOnly = true)
	public Optional<ClassRef> nextClass(long classId) {
		List<ClassRef> all = classesInOrder().stream().filter(ClassRef::active).toList();
		for (int i = 0; i < all.size() - 1; i++) {
			if (all.get(i).id() == classId) {
				return Optional.of(all.get(i + 1));
			}
		}
		return Optional.empty();
	}

	@Transactional(readOnly = true)
	public Optional<SectionRef> findSection(long id) {
		return jdbc.query("""
				select s.id, s.class_id, c.name, s.name, c.display_order, s.status = 'ACTIVE' and c.status = 'ACTIVE'
				from section s join school_class c on c.id = s.class_id where s.id = ?""",
				(rs, i) -> new SectionRef(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
						rs.getBoolean(6)),
				id).stream().findFirst();
	}

	public SectionRef requireSection(long id) {
		return findSection(id).orElseThrow(() -> ApiException.notFound("Section"));
	}

	@Transactional(readOnly = true)
	public List<SectionRef> sectionsOfClass(long classId) {
		return jdbc.query("""
				select s.id, s.class_id, c.name, s.name, c.display_order, s.status = 'ACTIVE'
				from section s join school_class c on c.id = s.class_id where s.class_id = ? order by s.name""",
				(rs, i) -> new SectionRef(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
						rs.getBoolean(6)),
				classId);
	}

	/** Sections a teacher is assigned to on a date (class teacher or any subject): the "own classes" scope. */
	@Transactional(readOnly = true)
	public Set<Long> sectionsTaughtBy(long staffId, long yearId, LocalDate on) {
		return Set.copyOf(jdbc.queryForList("""
				select distinct section_id from teaching_assignment
				where staff_id = ? and academic_year_id = ? and effective_from <= ?
				  and (effective_to is null or effective_to >= ?)""", Long.class, staffId, yearId,
				java.sql.Date.valueOf(on), java.sql.Date.valueOf(on)));
	}

	/** Sections where the teacher is the class teacher on a date: the registers they may take (QA-STF-001). */
	@Transactional(readOnly = true)
	public Set<Long> classTeacherSections(long staffId, long yearId, LocalDate on) {
		return Set.copyOf(jdbc.queryForList("""
				select distinct section_id from teaching_assignment
				where staff_id = ? and academic_year_id = ? and subject_id is null and effective_from <= ?
				  and (effective_to is null or effective_to >= ?)""", Long.class, staffId, yearId,
				java.sql.Date.valueOf(on), java.sql.Date.valueOf(on)));
	}

	/** Whether the staff member teaches the subject in the section on the date (marks entry, QA-EXM-003). */
	@Transactional(readOnly = true)
	public boolean teaches(long staffId, long yearId, long sectionId, long subjectId, LocalDate on) {
		Long n = jdbc.queryForObject("""
				select count(*) from teaching_assignment
				where staff_id = ? and academic_year_id = ? and section_id = ? and subject_id = ? and effective_from <= ?
				  and (effective_to is null or effective_to >= ?)""", Long.class, staffId, yearId, sectionId, subjectId,
				java.sql.Date.valueOf(on), java.sql.Date.valueOf(on));
		return n != null && n > 0;
	}
}
