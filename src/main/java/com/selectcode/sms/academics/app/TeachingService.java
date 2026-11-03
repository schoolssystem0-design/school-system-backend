package com.selectcode.sms.academics.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.events.StaffLeftEvent;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.staff.StaffService;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who teaches what (FR 6.3, 6.6, 9.5) and the weekly timetable (FR 6.4). Assignments are dated, so past attendance and
 * marks stay linked to the teacher who taught at the time.
 */
@Service
public class TeachingService {

	private static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	public record AssignmentView(long id, long sectionId, String sectionLabel, Long subjectId, String subjectName,
			long staffId, String staffName, LocalDate effectiveFrom, LocalDate effectiveTo) {

		public boolean classTeacher() {
			return subjectId == null;
		}
	}

	public record Period(int weekday, int periodNo, LocalTime startTime, LocalTime endTime, Long subjectId,
			Long staffId) {
	}

	public record PeriodView(long sectionId, String sectionLabel, int weekday, int periodNo, LocalTime startTime,
			LocalTime endTime, Long subjectId, String subjectName, Long staffId, String staffName) {
	}

	private final JdbcTemplate jdbc;

	private final AcademicStructure structure;

	private final StaffService staff;

	private final AcademicYearLookup years;

	private final YearContext yearContext;

	private final AuditService audit;

	private final Clock clock;

	TeachingService(JdbcTemplate jdbc, AcademicStructure structure, StaffService staff, AcademicYearLookup years,
			YearContext yearContext, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.structure = structure;
		this.staff = staff;
		this.years = years;
		this.yearContext = yearContext;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------ assignments

	@Transactional(readOnly = true)
	public List<AssignmentView> assignments(long yearId, Long sectionId, Long staffId, boolean currentOnly) {
		StringBuilder sql = new StringBuilder("""
				select a.id, a.section_id, c.name || ' - ' || s.name, a.subject_id, sub.name, a.staff_id, st.name,
				       a.effective_from, a.effective_to
				from teaching_assignment a
				join section s on s.id = a.section_id join school_class c on c.id = s.class_id
				left join subject sub on sub.id = a.subject_id
				join staff st on st.id = a.staff_id
				where a.academic_year_id = ?""");
		List<Object> args = new java.util.ArrayList<>(List.of(yearId));
		if (sectionId != null) {
			sql.append(" and a.section_id = ?");
			args.add(sectionId);
		}
		if (staffId != null) {
			sql.append(" and a.staff_id = ?");
			args.add(staffId);
		}
		if (currentOnly) {
			sql.append(" and a.effective_to is null");
		}
		sql.append(" order by c.display_order, s.name, a.subject_id nulls first, a.effective_from");
		return jdbc.query(sql.toString(), (rs, i) -> new AssignmentView(rs.getLong(1), rs.getLong(2), rs.getString(3),
				(Long) rs.getObject(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getDate(8).toLocalDate(),
				rs.getDate(9) == null ? null : rs.getDate(9).toLocalDate()), args.toArray());
	}

	/**
	 * Assigns the class teacher (subjectId null) or a subject teacher of a section from a date. The previous holder's
	 * assignment ends the day before (FR 6.6: past records stay with the previous teacher).
	 */
	@Transactional
	public AssignmentView assign(long yearId, long sectionId, Long subjectId, long staffId, LocalDate from) {
		YearRef year = writableYear(yearId);
		var section = structure.requireSection(sectionId);
		var teacher = staff.ref(staffId);
		if (!teacher.active()) {
			throw ApiException.conflict("STAFF_LEFT", teacher.name() + " has left the school");
		}
		if (subjectId != null) {
			Long taught = jdbc.queryForObject("""
					select count(*) from class_subject where academic_year_id = ? and class_id = ? and subject_id = ?
					  and status = 'ACTIVE'""", Long.class, yearId, section.classId(), subjectId);
			if (taught == null || taught == 0) {
				throw ApiException.businessRule("SUBJECT_NOT_IN_CLASS",
						"Add this subject to " + section.className() + " for " + year.name() + " first");
			}
		}
		LocalDate start = clampToYear(from, year);
		var current = open(yearId, sectionId, subjectId);
		if (current.isPresent()) {
			var existing = current.get();
			if (existing.staffId() == staffId) {
				return existing;
			}
			end(existing, start.minusDays(1));
		}
		Long id = jdbc.queryForObject("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), yearId, sectionId,
				subjectId, staffId, Date.valueOf(start));
		audit.record(subjectId == null ? "CLASS_TEACHER_ASSIGNED" : "SUBJECT_TEACHER_ASSIGNED", "section", sectionId,
				current.map(c -> Map.<String, Object>of("staff", c.staffName())).orElse(null),
				Map.of("staff", teacher.name(), "subjectId", String.valueOf(subjectId), "from", start.toString()));
		return assignments(yearId, sectionId, null, false).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
	}

	@Transactional
	public void endAssignment(long assignmentId, LocalDate to) {
		var a = jdbc.query("""
				select academic_year_id, effective_from, effective_to from teaching_assignment where id = ?""",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getDate(3) }, assignmentId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Assignment"));
		writableYear((Long) a[0]);
		LocalDate from = (LocalDate) a[1];
		LocalDate end = to == null ? today() : to;
		if (end.isBefore(from)) {
			// Never started: remove instead of leaving an empty period.
			jdbc.update("delete from teaching_assignment where id = ?", assignmentId);
		}
		else {
			jdbc.update("update teaching_assignment set effective_to = ? where id = ?", Date.valueOf(end), assignmentId);
		}
		audit.record("TEACHING_ASSIGNMENT_ENDED", "teaching_assignment", assignmentId, null,
				Map.of("to", end.toString()));
	}

	/** Moves every open assignment (and timetable period) of one teacher to another from a date (FR 6.6). */
	@Transactional
	public int handover(long yearId, long fromStaffId, long toStaffId, LocalDate from) {
		YearRef year = writableYear(yearId);
		if (fromStaffId == toStaffId) {
			throw ApiException.businessRule("SAME_TEACHER", "Choose a different teacher");
		}
		var replacement = staff.ref(toStaffId);
		if (!replacement.active()) {
			throw ApiException.conflict("STAFF_LEFT", replacement.name() + " has left the school");
		}
		LocalDate start = clampToYear(from, year);
		List<AssignmentView> open = assignments(yearId, null, fromStaffId, true);
		for (AssignmentView a : open) {
			var target = open(yearId, a.sectionId(), a.subjectId());
			target.ifPresent(t -> end(t, start.minusDays(1)));
			jdbc.update("""
					insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
					values (?, ?, ?, ?, ?, ?)""", TenantContext.requireSchoolId(), yearId, a.sectionId(), a.subjectId(),
					toStaffId, Date.valueOf(start));
		}
		int periods = jdbc.update("update timetable_period set staff_id = ? where academic_year_id = ? and staff_id = ?",
				toStaffId, yearId, fromStaffId);
		audit.record("TEACHING_HANDOVER", "staff", fromStaffId, null, Map.of("to", replacement.name(), "from",
				start.toString(), "assignments", open.size(), "periods", periods));
		return open.size();
	}

	/** A teacher who leaves stops teaching from the leaving date (FR 6.7). */
	@EventListener
	@Transactional
	public void onStaffLeft(StaffLeftEvent event) {
		jdbc.update("""
				delete from teaching_assignment where staff_id = ? and effective_to is null and effective_from > ?""",
				event.staffId(), Date.valueOf(event.leavingDate()));
		jdbc.update("""
				update teaching_assignment set effective_to = ? where staff_id = ? and effective_to is null""",
				Date.valueOf(event.leavingDate()), event.staffId());
		jdbc.update("update timetable_period set staff_id = null where staff_id = ?", event.staffId());
	}

	// -------------------------------------------------------------- timetable

	@Transactional(readOnly = true)
	public List<PeriodView> timetable(long yearId, Long sectionId, Long staffId) {
		StringBuilder sql = new StringBuilder("""
				select t.section_id, c.name || ' - ' || s.name, t.weekday, t.period_no, t.start_time, t.end_time,
				       t.subject_id, sub.name, t.staff_id, st.name
				from timetable_period t
				join section s on s.id = t.section_id join school_class c on c.id = s.class_id
				left join subject sub on sub.id = t.subject_id left join staff st on st.id = t.staff_id
				where t.academic_year_id = ?""");
		List<Object> args = new java.util.ArrayList<>(List.of(yearId));
		if (sectionId != null) {
			sql.append(" and t.section_id = ?");
			args.add(sectionId);
		}
		if (staffId != null) {
			sql.append(" and t.staff_id = ?");
			args.add(staffId);
		}
		sql.append(" order by t.weekday, t.period_no, c.display_order");
		return jdbc.query(sql.toString(), (rs, i) -> new PeriodView(rs.getLong(1), rs.getString(2), rs.getInt(3),
				rs.getInt(4), rs.getObject(5, LocalTime.class), rs.getObject(6, LocalTime.class),
				(Long) rs.getObject(7), rs.getString(8), (Long) rs.getObject(9), rs.getString(10)), args.toArray());
	}

	/** Replaces a section's weekly timetable; a teacher cannot be in two sections in the same period. */
	@Transactional
	public List<PeriodView> saveTimetable(long yearId, long sectionId, List<Period> periods) {
		writableYear(yearId);
		structure.requireSection(sectionId);
		Set<String> slots = new HashSet<>();
		for (Period p : periods) {
			if (p.weekday() < 1 || p.weekday() > 7 || p.periodNo() < 1 || p.periodNo() > 15) {
				throw ApiException.businessRule("PERIOD_INVALID", "Weekday 1-7 and period 1-15");
			}
			if (!slots.add(p.weekday() + ":" + p.periodNo())) {
				throw ApiException.businessRule("PERIOD_DUPLICATE", "A period appears twice");
			}
			if (p.staffId() != null) {
				Long clash = jdbc.queryForObject("""
						select count(*) from timetable_period where academic_year_id = ? and staff_id = ?
						  and weekday = ? and period_no = ? and section_id <> ?""", Long.class, yearId, p.staffId(),
						p.weekday(), p.periodNo(), sectionId);
				if (clash != null && clash > 0) {
					throw ApiException.conflict("TEACHER_DOUBLE_BOOKED", staff.ref(p.staffId()).name()
							+ " already teaches another section on day " + p.weekday() + ", period " + p.periodNo());
				}
			}
		}
		jdbc.update("delete from timetable_period where academic_year_id = ? and section_id = ?", yearId, sectionId);
		long schoolId = TenantContext.requireSchoolId();
		for (Period p : periods) {
			jdbc.update("""
					insert into timetable_period (school_id, academic_year_id, section_id, weekday, period_no, start_time,
					                              end_time, subject_id, staff_id)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, yearId, sectionId, p.weekday(), p.periodNo(),
					p.startTime(), p.endTime(), p.subjectId(), p.staffId());
		}
		audit.record("TIMETABLE_SAVED", "section", sectionId, null, Map.of("periods", periods.size()));
		return timetable(yearId, sectionId, null);
	}

	// ---------------------------------------------------------------- helpers

	private java.util.Optional<AssignmentView> open(long yearId, long sectionId, Long subjectId) {
		return assignments(yearId, sectionId, null, true).stream()
			.filter(a -> java.util.Objects.equals(a.subjectId(), subjectId))
			.findFirst();
	}

	private void end(AssignmentView a, LocalDate to) {
		if (to.isBefore(a.effectiveFrom())) {
			jdbc.update("delete from teaching_assignment where id = ?", a.id());
		}
		else {
			jdbc.update("update teaching_assignment set effective_to = ? where id = ?", Date.valueOf(to), a.id());
		}
	}

	private YearRef writableYear(long yearId) {
		YearRef year = years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		yearContext.requireWritable(year);
		return year;
	}

	private LocalDate clampToYear(LocalDate from, YearRef year) {
		LocalDate date = from == null ? today() : from;
		if (date.isBefore(year.startDate())) {
			return year.startDate();
		}
		if (date.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The date is after the end of " + year.name());
		}
		return date;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}
}
