package com.selectcode.sms.attendance.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.attendance.StaffAttendanceSummary;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily staff attendance with arrival times (FR 13.9), corrections (FR 13.10) and the monthly summary that, once
 * approved, is the only source payroll uses (FR 13.11, 13.12, QA-ATT-010). Late = arrival after the late time for the
 * staff member's category (teachers / other staff, Rules & settings).
 */
@Service
public class StaffAttendanceService implements StaffAttendanceSummary {

	public record StaffDayRow(long staffId, String employeeNo, String name, String category, String designation,
			String status, boolean saved, LocalTime arrivalTime, String note, boolean onApprovedLeave,
			boolean monthApproved) {
	}

	public record StaffDay(LocalDate date, boolean workingDay, LocalTime teacherLateAfter, LocalTime otherLateAfter,
			List<StaffDayRow> rows) {
	}

	public record StaffMark(long staffId, String status, LocalTime arrivalTime, String note) {
	}

	public record StaffMonthRow(long staffId, String employeeNo, String name, String category, int workingDays, int present,
			int late, int halfDays, int absent, int leave, int unmarked, boolean approved, String approvedBy,
			Instant approvedAt) {
	}

	private final JdbcTemplate jdbc;

	private final WorkingDayCalendar calendar;

	private final SettingsService settings;

	private final AuditService audit;

	private final Clock clock;

	StaffAttendanceService(JdbcTemplate jdbc, WorkingDayCalendar calendar, SettingsService settings, AuditService audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.calendar = calendar;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	// -------------------------------------------------------------------- day

	@Transactional(readOnly = true)
	public StaffDay day(LocalDate date) {
		Map<Long, Object[]> saved = new HashMap<>();
		jdbc.query("select staff_id, status, arrival_time, note from staff_attendance where att_date = ?", rs -> {
			saved.put(rs.getLong(1), new Object[] { rs.getString(2),
					rs.getTime(3) == null ? null : rs.getTime(3).toLocalTime(), rs.getString(4) });
		}, Date.valueOf(date));
		Set<Long> onLeave = Set.copyOf(jdbc.queryForList("""
				select staff_id from leave_application where person_type = 'STAFF' and status = 'APPROVED'
				  and date_from <= ? and date_to >= ?""", Long.class, Date.valueOf(date), Date.valueOf(date)));
		Set<Long> approved = Set.copyOf(jdbc.queryForList("select staff_id from staff_month_summary where month = ?",
				Long.class, YearMonth.from(date).toString()));
		List<StaffDayRow> rows = jdbc.query("""
				select id, employee_no, name, category, designation from staff
				where joining_date <= ? and (leaving_date is null or leaving_date >= ?) order by category desc, name""",
				(rs, i) -> {
					long id = rs.getLong(1);
					Object[] s = saved.get(id);
					boolean leave = onLeave.contains(id);
					return new StaffDayRow(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
							s != null ? (String) s[0] : leave ? "LV" : null, s != null,
							s == null ? null : (LocalTime) s[1], s == null ? null : (String) s[2], leave,
							approved.contains(id));
				}, Date.valueOf(date), Date.valueOf(date));
		return new StaffDay(date, calendar.isWorkingDay(date), settings.time(SettingKey.TEACHING_STAFF_LATE_TIME),
				settings.time(SettingKey.OTHER_STAFF_LATE_TIME), rows);
	}

	/**
	 * Saves the given staff rows. Present with an arrival after the category's late time becomes late. Changing a
	 * status saved on an earlier day needs the correction permission and a reason; an approved month is locked.
	 */
	@Transactional
	public StaffDay save(LocalDate date, List<StaffMark> marks, String reason) {
		LocalDate today = MoneyInput.today(clock);
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "Attendance cannot be taken for a future day");
		}
		String why = PakFormats.trimToNull(reason);
		boolean canCorrect = CurrentActor.hasPermission(Permissions.ATTENDANCE_CORRECT);
		if (!calendar.isWorkingDay(date) && (!canCorrect || why == null)) {
			throw ApiException.businessRule("NOT_WORKING_DAY",
					"The school is closed on this day. Only someone allowed to correct attendance can mark it, with a reason.");
		}
		StaffDay current = day(date);
		Map<Long, StaffDayRow> rows = new HashMap<>();
		current.rows().forEach(r -> rows.put(r.staffId(), r));
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		for (StaffMark m : marks) {
			StaffDayRow row = rows.get(m.staffId());
			if (row == null) {
				throw ApiException.businessRule("NOT_STAFF", "A staff member is not employed on this day");
			}
			if (row.monthApproved()) {
				throw ApiException.conflict("MONTH_APPROVED",
						row.name() + "'s attendance for " + YearMonth.from(date) + " is approved; reopen the month first");
			}
			if (m.status() == null || !RegisterService.STATUSES.contains(m.status())) {
				throw ApiException.businessRule("STATUS_INVALID", "Use P, L, HD, A or LV");
			}
			String status = m.status();
			LocalTime lateAfter = "TEACHER".equals(row.category()) ? current.teacherLateAfter() : current.otherLateAfter();
			if ("P".equals(status) && m.arrivalTime() != null && m.arrivalTime().isAfter(lateAfter)) {
				status = "L";
			}
			if (row.saved() && !status.equals(row.status())) {
				if (date.isBefore(today) && (!canCorrect || why == null)) {
					throw ApiException.businessRule("CORRECTION_REASON_REQUIRED",
							"Changing attendance of an earlier day needs the correction permission and a reason");
				}
				jdbc.update("""
						insert into attendance_correction (school_id, person_type, person_id, att_date, old_status,
						                                   new_status, reason, corrected_by)
						values (?, 'STAFF', ?, ?, ?, ?, ?, ?)""", schoolId, m.staffId(), Date.valueOf(date), row.status(),
						status, why == null ? "Changed on the same day" : why, actor);
			}
			jdbc.update("""
					insert into staff_attendance (school_id, att_date, staff_id, status, arrival_time, note, marked_by)
					values (?, ?, ?, ?, ?, ?, ?)
					on conflict (school_id, staff_id, att_date) do update set status = excluded.status,
					    arrival_time = excluded.arrival_time, note = excluded.note, marked_by = excluded.marked_by,
					    marked_at = now()""", schoolId, Date.valueOf(date), m.staffId(), status,
					m.arrivalTime() == null ? null : Time.valueOf(m.arrivalTime()), PakFormats.trimToNull(m.note()), actor);
		}
		audit.record("STAFF_ATTENDANCE_SAVED", "staff_attendance", null, null,
				Map.of("date", date.toString(), "staff", marks.size()), why);
		return day(date);
	}

	// ------------------------------------------------------------------ month

	@Transactional(readOnly = true)
	public List<StaffMonthRow> month(YearMonth month) {
		LocalDate first = month.atDay(1);
		LocalDate today = MoneyInput.today(clock);
		LocalDate last = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
		List<LocalDate> working = last.isBefore(first) ? List.of() : calendar.workingDays(first, last);
		List<StaffMonthRow> rows = new ArrayList<>();
		for (Map<String, Object> s : jdbc.queryForList("""
				select id, employee_no, name, category, joining_date, leaving_date from staff
				where joining_date <= ? and (leaving_date is null or leaving_date >= ?) order by category desc, name""",
				Date.valueOf(month.atEndOfMonth()), Date.valueOf(first))) {
			long staffId = ((Number) s.get("id")).longValue();
			var saved = jdbc.queryForList("""
					select summary.* from staff_month_summary summary where staff_id = ? and month = ?""", staffId,
					month.toString());
			if (!saved.isEmpty()) {
				Map<String, Object> a = saved.getFirst();
				rows.add(new StaffMonthRow(staffId, (String) s.get("employee_no"), (String) s.get("name"),
						(String) s.get("category"), n(a, "working_days"), n(a, "present_days"), n(a, "late_days"),
						n(a, "half_days"), n(a, "absent_days"), n(a, "leave_days"), n(a, "unmarked_days"), true,
						(String) a.get("approved_by"), ((java.sql.Timestamp) a.get("approved_at")).toInstant()));
				continue;
			}
			LocalDate joined = ((Date) s.get("joining_date")).toLocalDate();
			LocalDate left = s.get("leaving_date") == null ? null : ((Date) s.get("leaving_date")).toLocalDate();
			List<LocalDate> employed = working.stream()
				.filter(d -> !d.isBefore(joined) && (left == null || !d.isAfter(left)))
				.toList();
			Map<String, Integer> counts = new HashMap<>();
			jdbc.query("""
					select att_date, status from staff_attendance where staff_id = ? and att_date between ? and ?""",
					rs -> {
						if (employed.contains(rs.getDate(1).toLocalDate())) {
							counts.merge(rs.getString(2), 1, Integer::sum);
						}
					}, staffId, Date.valueOf(first), Date.valueOf(month.atEndOfMonth()));
			int marked = counts.values().stream().mapToInt(Integer::intValue).sum();
			rows.add(new StaffMonthRow(staffId, (String) s.get("employee_no"), (String) s.get("name"),
					(String) s.get("category"), employed.size(), counts.getOrDefault("P", 0), counts.getOrDefault("L", 0),
					counts.getOrDefault("HD", 0), counts.getOrDefault("A", 0), counts.getOrDefault("LV", 0),
					employed.size() - marked, false, null, null));
		}
		return rows;
	}

	/**
	 * Approves the month for the given staff (all if none given). Days not marked block approval unless
	 * {@code unmarkedAsPresent}; then they are recorded as present in the summary.
	 */
	@Transactional
	public List<StaffMonthRow> approve(YearMonth month, List<Long> staffIds, boolean unmarkedAsPresent) {
		if (month.isAfter(YearMonth.from(MoneyInput.today(clock)))) {
			throw ApiException.businessRule("MONTH_IN_FUTURE", "A future month cannot be approved");
		}
		List<StaffMonthRow> rows = month(month).stream()
			.filter(r -> !r.approved() && (staffIds == null || staffIds.isEmpty() || staffIds.contains(r.staffId())))
			.toList();
		List<String> unmarked = rows.stream().filter(r -> r.unmarked() > 0).map(StaffMonthRow::name).toList();
		if (!unmarked.isEmpty() && !unmarkedAsPresent) {
			throw ApiException.businessRule("UNMARKED_DAYS",
					"Some days are not marked for: " + String.join(", ", unmarked.subList(0, Math.min(5, unmarked.size())))
							+ (unmarked.size() > 5 ? " and others" : "") + ". Mark them, or count unmarked days as present.");
		}
		String actor = CurrentActor.require().displayName();
		for (StaffMonthRow r : rows) {
			int present = r.present() + (unmarkedAsPresent ? r.unmarked() : 0);
			jdbc.update("""
					insert into staff_month_summary (school_id, staff_id, month, working_days, present_days, late_days,
					                                 half_days, absent_days, leave_days, unmarked_days, approved_by)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireSchoolId(), r.staffId(),
					month.toString(), r.workingDays(), present, r.late(), r.halfDays(), r.absent(), r.leave(),
					unmarkedAsPresent ? 0 : r.unmarked(), actor);
		}
		audit.record("STAFF_MONTH_APPROVED", "staff_month_summary", null, null,
				Map.of("month", month.toString(), "staff", rows.size(), "unmarkedAsPresent", unmarkedAsPresent));
		return month(month);
	}

	/** Reopens an approved month (all staff), unless payroll for the month is already approved or paid. */
	@Transactional
	public List<StaffMonthRow> reopen(YearMonth month, String reason) {
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for reopening");
		}
		Long payroll = jdbc.queryForObject(
				"select count(*) from salary_sheet where month = ? and status in ('APPROVED', 'PAID')", Long.class,
				month.toString());
		if (payroll != null && payroll > 0) {
			throw ApiException.conflict("PAYROLL_APPROVED",
					"Salaries for " + month + " are approved; reverse the salary sheet before reopening attendance");
		}
		int removed = jdbc.update("delete from staff_month_summary where month = ?", month.toString());
		audit.record("STAFF_MONTH_REOPENED", "staff_month_summary", null, null,
				Map.of("month", month.toString(), "staff", removed), why);
		return month(month);
	}

	/** Payroll hook (D-36): only an approved summary counts. */
	@Override
	@Transactional(readOnly = true)
	public Optional<MonthTotals> approvedTotals(long staffId, YearMonth month) {
		return jdbc.query("""
				select absent_days, half_days, late_days, leave_days from staff_month_summary
				where staff_id = ? and month = ?""",
				(rs, i) -> new MonthTotals(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), staffId,
				month.toString()).stream().findFirst();
	}

	private static int n(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).intValue();
	}
}
