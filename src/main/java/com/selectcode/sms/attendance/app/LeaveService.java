package com.selectcode.sms.attendance.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave applications for students and staff, entered by the office (FR 13.8; no portal, D-19). Once approved, those
 * working days count as leave, including days already marked absent (changed with a correction record, QA-ATT-008).
 */
@Service
public class LeaveService {

	public static final Set<String> TYPES = Set.of("SICK", "CASUAL", "FAMILY", "OFFICIAL", "OTHER");

	public record LeaveInput(String personType, Long studentId, Long staffId, String leaveType, LocalDate dateFrom,
			LocalDate dateTo, String reason) {
	}

	public record LeaveView(long id, String personType, Long studentId, Long staffId, String personName,
			String detail, String leaveType, LocalDate dateFrom, LocalDate dateTo, int workingDays, String reason,
			String status, String enteredBy, Instant enteredAt, String decidedBy, Instant decidedAt,
			String decisionNote) {
	}

	private final JdbcTemplate jdbc;

	private final WorkingDayCalendar calendar;

	private final AuditService audit;

	private final Clock clock;

	LeaveService(JdbcTemplate jdbc, WorkingDayCalendar calendar, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.calendar = calendar;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public LeaveView create(LeaveInput in) {
		boolean student = "STUDENT".equals(in.personType());
		if (!student && !"STAFF".equals(in.personType())) {
			throw ApiException.businessRule("PERSON_INVALID", "Choose a student or a staff member");
		}
		Long personId = student ? in.studentId() : in.staffId();
		if (personId == null) {
			throw ApiException.businessRule("PERSON_REQUIRED", "Choose who is on leave");
		}
		Long exists = jdbc.queryForObject(student ? "select count(*) from student where id = ?"
				: "select count(*) from staff where id = ?", Long.class, personId);
		if (exists == null || exists == 0) {
			throw ApiException.notFound(student ? "Student" : "Staff member");
		}
		if (in.leaveType() == null || !TYPES.contains(in.leaveType())) {
			throw ApiException.businessRule("TYPE_INVALID", "Choose the kind of leave");
		}
		if (in.dateFrom() == null || in.dateTo() == null || in.dateTo().isBefore(in.dateFrom())
				|| ChronoUnit.DAYS.between(in.dateFrom(), in.dateTo()) > 120) {
			throw ApiException.businessRule("DATES_INVALID", "Choose the first and last day (up to four months)");
		}
		String reason = PakFormats.trimToNull(in.reason());
		if (reason == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the leave");
		}
		Long overlap = jdbc.queryForObject("""
				select count(*) from leave_application where status in ('PENDING', 'APPROVED')
				  and (student_id = ? or staff_id = ?) and person_type = ? and date_from <= ? and date_to >= ?""",
				Long.class, student ? personId : -1, student ? -1 : personId, in.personType(), Date.valueOf(in.dateTo()),
				Date.valueOf(in.dateFrom()));
		if (overlap != null && overlap > 0) {
			throw ApiException.conflict("LEAVE_OVERLAP", "There is already leave for some of these days");
		}
		int days = calendar.countWorkingDays(in.dateFrom(), in.dateTo());
		Long id = jdbc.queryForObject("""
				insert into leave_application (school_id, person_type, student_id, staff_id, leave_type, date_from,
				                               date_to, working_days, reason, status, entered_by)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?) returning id""", Long.class,
				TenantContext.requireSchoolId(), in.personType(), student ? personId : null, student ? null : personId,
				in.leaveType(), Date.valueOf(in.dateFrom()), Date.valueOf(in.dateTo()), days, reason,
				CurrentActor.require().displayName());
		audit.record("LEAVE_ENTERED", "leave_application", id, null, Map.of("personType", in.personType(),
				"personId", personId, "from", in.dateFrom().toString(), "to", in.dateTo().toString()), reason);
		if (CurrentActor.hasPermission(Permissions.ATTENDANCE_LEAVE_APPROVE)) {
			return decide(id, true, "Approved when entered");
		}
		return get(id);
	}

	@Transactional
	public LeaveView decide(long id, boolean approve, String note) {
		LeaveView leave = get(id);
		if (!"PENDING".equals(leave.status())) {
			throw ApiException.conflict("ALREADY_DECIDED", "This leave is already " + leave.status().toLowerCase());
		}
		String clean = PakFormats.trimToNull(note);
		if (!approve && clean == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for rejecting");
		}
		boolean staff = "STAFF".equals(leave.personType());
		if (approve && staff) {
			for (YearMonth m = YearMonth.from(leave.dateFrom()); !m.isAfter(YearMonth.from(leave.dateTo())); m = m.plusMonths(1)) {
				Long locked = jdbc.queryForObject(
						"select count(*) from staff_month_summary where staff_id = ? and month = ?", Long.class,
						leave.staffId(), m.toString());
				if (locked != null && locked > 0) {
					throw ApiException.conflict("MONTH_APPROVED",
							"Staff attendance for " + m + " is already approved; reopen it first");
				}
			}
		}
		String actor = CurrentActor.require().displayName();
		jdbc.update("""
				update leave_application set status = ?, decided_by = ?, decided_at = ?, decision_note = ? where id = ?""",
				approve ? "APPROVED" : "REJECTED", actor, Timestamp.from(clock.instant()), clean, id);
		int changed = 0;
		if (approve) {
			String table = staff ? "staff_attendance" : "student_attendance";
			String column = staff ? "staff_id" : "student_id";
			long personId = staff ? leave.staffId() : leave.studentId();
			List<Object[]> marked = jdbc.query("select att_date, status from " + table + " where " + column
					+ " = ? and att_date between ? and ? and status <> 'LV'",
					(rs, i) -> new Object[] { rs.getDate(1), rs.getString(2) }, personId, Date.valueOf(leave.dateFrom()),
					Date.valueOf(leave.dateTo()));
			for (Object[] m : marked) {
				jdbc.update("update " + table + " set status = 'LV', marked_by = ?, marked_at = now() where " + column
						+ " = ? and att_date = ?", actor, personId, m[0]);
				jdbc.update("""
						insert into attendance_correction (school_id, person_type, person_id, att_date, old_status,
						                                   new_status, reason, corrected_by)
						values (?, ?, ?, ?, ?, 'LV', ?, ?)""", TenantContext.requireSchoolId(), leave.personType(),
						personId, m[0], m[1], "Approved leave #" + id, actor);
				changed++;
			}
		}
		audit.record(approve ? "LEAVE_APPROVED" : "LEAVE_REJECTED", "leave_application", id, null,
				Map.of("status", approve ? "APPROVED" : "REJECTED", "daysChanged", changed), clean);
		return get(id);
	}

	/** A pending leave, or an approved one that has not started, can be withdrawn. */
	@Transactional
	public LeaveView cancel(long id, String reason) {
		LeaveView leave = get(id);
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason");
		}
		boolean future = leave.dateFrom().isAfter(MoneyInput.today(clock));
		if (!"PENDING".equals(leave.status()) && !("APPROVED".equals(leave.status()) && future)) {
			throw ApiException.conflict("CANNOT_CANCEL",
					"Leave that has started can only be changed by correcting the attendance");
		}
		jdbc.update("update leave_application set status = 'CANCELLED', decision_note = ? where id = ?", why, id);
		audit.record("LEAVE_CANCELLED", "leave_application", id, Map.of("status", leave.status()),
				Map.of("status", "CANCELLED"), why);
		return get(id);
	}

	@Transactional(readOnly = true)
	public List<LeaveView> list(String status, String personType, Long personId, LocalDate from, LocalDate to) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder(SELECT).append(" where 1 = 1");
		if (status != null && !status.isBlank()) {
			sql.append(" and l.status = ?");
			args.add(status);
		}
		if (personType != null && !personType.isBlank()) {
			sql.append(" and l.person_type = ?");
			args.add(personType);
		}
		if (personId != null) {
			sql.append(" and (l.student_id = ? or l.staff_id = ?)");
			args.add(personId);
			args.add(personId);
		}
		if (from != null) {
			sql.append(" and l.date_to >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			sql.append(" and l.date_from <= ?");
			args.add(Date.valueOf(to));
		}
		sql.append(" order by case l.status when 'PENDING' then 0 else 1 end, l.date_from desc limit 500");
		return jdbc.query(sql.toString(), (rs, i) -> view(rs), args.toArray());
	}

	public LeaveView get(long id) {
		List<LeaveView> rows = jdbc.query(SELECT + " where l.id = ?", (rs, i) -> view(rs), id);
		if (rows.isEmpty()) {
			throw ApiException.notFound("Leave");
		}
		return rows.getFirst();
	}

	private static final String SELECT = """
			select l.id, l.person_type, l.student_id, l.staff_id, coalesce(s.name, st.name),
			       case when l.person_type = 'STUDENT' then 'GR ' || s.gr_no else st.employee_no || ' · ' || st.designation end,
			       l.leave_type, l.date_from, l.date_to, l.working_days, l.reason, l.status, l.entered_by, l.entered_at,
			       l.decided_by, l.decided_at, l.decision_note
			from leave_application l left join student s on s.id = l.student_id left join staff st on st.id = l.staff_id""";

	private static LeaveView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new LeaveView(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getDate(8).toLocalDate(),
				rs.getDate(9).toLocalDate(), rs.getInt(10), rs.getString(11), rs.getString(12), rs.getString(13),
				rs.getTimestamp(14).toInstant(), rs.getString(15),
				rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toInstant(), rs.getString(17));
	}
}
