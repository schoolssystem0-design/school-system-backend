package com.selectcode.sms.students.infra;

import com.selectcode.sms.shared.tenant.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Dated class membership (FR 5.4, 5.5, 9.6). A student has at most one enrolment on any date (database exclusion
 * constraint), so "which class was this student in on 3 October?" always has one answer.
 */
@Repository
public class Enrolments {

	public record Enrolment(long id, long studentId, long yearId, String yearName, long classId, String className,
			long sectionId, String sectionName, String rollNo, LocalDate effectiveFrom, LocalDate effectiveTo,
			String kind, String reason) {
	}

	private static final String SELECT = """
			select e.id, e.student_id, e.academic_year_id, y.name, e.class_id, c.name, e.section_id, s.name, e.roll_no,
			       e.effective_from, e.effective_to, e.kind, e.reason
			from enrolment e
			join academic_year y on y.id = e.academic_year_id
			join school_class c on c.id = e.class_id
			join section s on s.id = e.section_id
			""";

	private final JdbcTemplate jdbc;

	Enrolments(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Enrolment> history(long studentId) {
		return jdbc.query(SELECT + " where e.student_id = ? order by e.effective_from desc", this::map, studentId);
	}

	/** The enrolment in force on a date, if any. */
	public Optional<Enrolment> on(long studentId, LocalDate date) {
		return jdbc.query(SELECT + """
				 where e.student_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)""",
				this::map, studentId, Date.valueOf(date), Date.valueOf(date)).stream().findFirst();
	}

	/** The latest enrolment within a year (for "current class" in that year). */
	public Optional<Enrolment> latestInYear(long studentId, long yearId) {
		return jdbc.query(SELECT + " where e.student_id = ? and e.academic_year_id = ? order by e.effective_from desc limit 1",
				this::map, studentId, yearId).stream().findFirst();
	}

	public long create(long studentId, long yearId, long classId, long sectionId, String rollNo, LocalDate from,
			LocalDate to, String kind, String reason) {
		Long id = jdbc.queryForObject("""
				insert into enrolment (school_id, student_id, academic_year_id, class_id, section_id, roll_no,
				                       effective_from, effective_to, kind, reason)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
				studentId, yearId, classId, sectionId, rollNo, Date.valueOf(from), to == null ? null : Date.valueOf(to),
				kind, reason);
		return id == null ? 0 : id;
	}

	public void close(long enrolmentId, LocalDate to) {
		jdbc.update("update enrolment set effective_to = ? where id = ?", Date.valueOf(to), enrolmentId);
	}

	public void reopen(long enrolmentId) {
		jdbc.update("update enrolment set effective_to = null where id = ?", enrolmentId);
	}

	/** Same-day correction of a wrong section (no history kept for a mistake on the first day). */
	public void changeSection(long enrolmentId, long classId, long sectionId, String reason) {
		jdbc.update("update enrolment set class_id = ?, section_id = ?, reason = ? where id = ?", classId, sectionId,
				reason, enrolmentId);
	}

	public void setRollNo(long enrolmentId, String rollNo) {
		jdbc.update("update enrolment set roll_no = ? where id = ?", rollNo, enrolmentId);
	}

	private Enrolment map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Enrolment(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
				rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getDate(10).toLocalDate(),
				rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(), rs.getString(12), rs.getString(13));
	}
}
