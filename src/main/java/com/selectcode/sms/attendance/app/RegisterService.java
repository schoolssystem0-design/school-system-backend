package com.selectcode.sms.attendance.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import com.selectcode.sms.shared.year.YearContext;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily class register (FR 13.1–13.3). Everyone is present unless marked otherwise; saving the register marks it
 * taken. A change to a status already saved on an earlier day is a correction: it needs the correction permission and
 * a reason, and the old and new values are kept (QA-ATT-004).
 */
@Service
public class RegisterService {

	public static final Set<String> STATUSES = Set.of("P", "L", "HD", "A", "LV");

	public record RegisterEntry(long studentId, String grNo, String name, String rollNo, String status, boolean saved,
			LocalTime arrivalTime, String note, boolean onApprovedLeave) {
	}

	public record RegisterView(long sectionId, String sectionLabel, LocalDate date, boolean workingDay,
			boolean completed, String completedBy, Instant completedAt, boolean canMark, boolean needsCorrection,
			List<RegisterEntry> rows, Map<String, Integer> totals) {
	}

	public record Mark(long studentId, String status, LocalTime arrivalTime, String note) {
	}

	public record MissingRegister(long sectionId, String sectionLabel, LocalDate date, int students,
			String classTeacher) {
	}

	public record CorrectionRow(long id, String personType, long personId, String personName, LocalDate date,
			String oldStatus, String newStatus, String reason, String correctedBy, Instant correctedAt) {
	}

	public record BulkInput(LocalDate from, LocalDate to, Long classId, Long sectionId, String fromStatus,
			String toStatus, boolean fillUnmarked, String reason) {
	}

	public record BulkResult(int days, int changed, int created) {
	}

	private final JdbcTemplate jdbc;

	private final AttendanceAccess access;

	private final WorkingDayCalendar calendar;

	private final YearContext yearContext;

	private final SettingsService settings;

	private final MessageOutbox outbox;

	private final MessageTemplates templates;

	private final AuditService audit;

	private final Clock clock;

	RegisterService(JdbcTemplate jdbc, AttendanceAccess access, WorkingDayCalendar calendar, YearContext yearContext,
			SettingsService settings, MessageOutbox outbox, MessageTemplates templates, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.calendar = calendar;
		this.yearContext = yearContext;
		this.settings = settings;
		this.outbox = outbox;
		this.templates = templates;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------- read

	@Transactional(readOnly = true)
	public RegisterView register(long sectionId, LocalDate date) {
		YearRef year = access.yearOf(date);
		access.checkView(year, date, sectionId);
		String label = access.sectionLabel(sectionId);
		Map<Long, Object[]> saved = new HashMap<>();
		jdbc.query("""
				select student_id, status, arrival_time, note from student_attendance
				where section_id = ? and att_date = ?""", rs -> {
			saved.put(rs.getLong(1), new Object[] { rs.getString(2),
					rs.getTime(3) == null ? null : rs.getTime(3).toLocalTime(), rs.getString(4) });
		}, sectionId, Date.valueOf(date));
		Set<Long> onLeave = Set.copyOf(jdbc.queryForList("""
				select student_id from leave_application where person_type = 'STUDENT' and status = 'APPROVED'
				  and date_from <= ? and date_to >= ?""", Long.class, Date.valueOf(date), Date.valueOf(date)));
		List<RegisterEntry> rows = jdbc.query("""
				select s.id, s.gr_no, s.name, e.roll_no from enrolment e join student s on s.id = e.student_id
				where e.section_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				order by nullif(regexp_replace(coalesce(e.roll_no, ''), '\\D', '', 'g'), '')::int nulls last, s.name""",
				(rs, i) -> {
					long id = rs.getLong(1);
					Object[] s = saved.get(id);
					boolean leave = onLeave.contains(id);
					return new RegisterEntry(id, rs.getString(2), rs.getString(3), rs.getString(4),
							s != null ? (String) s[0] : leave ? "LV" : "P", s != null,
							s == null ? null : (LocalTime) s[1], s == null ? null : (String) s[2], leave);
				}, sectionId, Date.valueOf(date), Date.valueOf(date));
		var completion = jdbc.queryForList(
				"select completed_by, completed_at from register_completion where section_id = ? and att_date = ?",
				sectionId, Date.valueOf(date));
		Map<String, Integer> totals = new HashMap<>();
		rows.forEach(r -> totals.merge(r.status(), 1, Integer::sum));
		LocalDate today = MoneyInput.today(clock);
		return new RegisterView(sectionId, label, date, calendar.isWorkingDay(date), !completion.isEmpty(),
				completion.isEmpty() ? null : (String) completion.getFirst().get("completed_by"),
				completion.isEmpty() ? null : ((java.sql.Timestamp) completion.getFirst().get("completed_at")).toInstant(),
				access.canMark(year, date, sectionId) && !date.isAfter(today) && !year.closed(),
				date.isBefore(today) && !completion.isEmpty(), rows, totals);
	}

	// ------------------------------------------------------------------- save

	/**
	 * Saves the register: students not in {@code marks} keep their saved status or are present (on approved leave:
	 * leave). {@code reason} is required to change statuses saved on an earlier day, and to mark a holiday.
	 */
	@Transactional
	public RegisterView save(long sectionId, LocalDate date, List<Mark> marks, String reason) {
		LocalDate today = MoneyInput.today(clock);
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "Attendance cannot be taken for a future day");
		}
		YearRef year = access.yearOf(date);
		yearContext.requireWritable(year);
		if (!access.canMark(year, date, sectionId)) {
			access.checkView(year, date, sectionId);
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_CLASS_TEACHER",
					"Only the class teacher (or the office) takes this register");
		}
		String why = PakFormats.trimToNull(reason);
		boolean canCorrect = CurrentActor.hasPermission(Permissions.ATTENDANCE_CORRECT);
		if (!calendar.isWorkingDay(date)) {
			if (!canCorrect || why == null) {
				throw ApiException.businessRule("NOT_WORKING_DAY",
						"The school is closed on this day. Only someone allowed to correct attendance can mark it, with a reason.");
			}
		}
		RegisterView current = register(sectionId, date);
		Map<Long, RegisterEntry> rows = new HashMap<>();
		current.rows().forEach(r -> rows.put(r.studentId(), r));
		Map<Long, Mark> byStudent = new HashMap<>();
		for (Mark m : marks == null ? List.<Mark>of() : marks) {
			if (!rows.containsKey(m.studentId())) {
				throw ApiException.businessRule("NOT_IN_SECTION", "A student is not in this section on this day");
			}
			if (m.status() == null || !STATUSES.contains(m.status())) {
				throw ApiException.businessRule("STATUS_INVALID", "Use P, L, HD, A or LV");
			}
			byStudent.put(m.studentId(), m);
		}
		LocalTime lateAfter = settings.time(SettingKey.STUDENT_LATE_TIME_1);
		boolean earlierDay = date.isBefore(today);
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		List<Long> newlyAbsent = new ArrayList<>();
		int corrections = 0;
		for (RegisterEntry row : current.rows()) {
			Mark m = byStudent.get(row.studentId());
			String status = m != null ? m.status() : row.status();
			LocalTime arrival = m != null ? m.arrivalTime() : row.arrivalTime();
			if ("P".equals(status) && arrival != null && arrival.isAfter(lateAfter)) {
				status = "L";
			}
			String note = m != null ? PakFormats.trimToNull(m.note()) : row.note();
			if (row.saved() && !status.equals(row.status())) {
				if (earlierDay && (!canCorrect || why == null)) {
					throw ApiException.businessRule("CORRECTION_REASON_REQUIRED",
							"Changing attendance of an earlier day needs the correction permission and a reason");
				}
				jdbc.update("""
						insert into attendance_correction (school_id, person_type, person_id, att_date, old_status,
						                                   new_status, reason, corrected_by)
						values (?, 'STUDENT', ?, ?, ?, ?, ?, ?)""", schoolId, row.studentId(), Date.valueOf(date),
						row.status(), status, why == null ? "Changed on the same day" : why, actor);
				corrections++;
			}
			if ("A".equals(status) && !(row.saved() && "A".equals(row.status()))) {
				newlyAbsent.add(row.studentId());
			}
			jdbc.update("""
					insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status,
					                                arrival_time, note, marked_by)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?)
					on conflict (school_id, student_id, att_date) do update
					set status = excluded.status, arrival_time = excluded.arrival_time, note = excluded.note,
					    section_id = excluded.section_id, marked_by = excluded.marked_by, marked_at = now()""",
					schoolId, year.id(), Date.valueOf(date), sectionId, row.studentId(), status,
					arrival == null ? null : Time.valueOf(arrival), note, actor);
		}
		jdbc.update("""
				insert into register_completion (school_id, section_id, att_date, completed_by) values (?, ?, ?, ?)
				on conflict (school_id, section_id, att_date) do update set completed_by = excluded.completed_by,
				    completed_at = now()""", schoolId, sectionId, Date.valueOf(date), actor);
		audit.record(current.completed() ? "REGISTER_UPDATED" : "REGISTER_TAKEN", "section", sectionId, null,
				Map.of("date", date.toString(), "students", current.rows().size(), "corrections", corrections), why);
		if (settings.bool(SettingKey.SEND_ABSENCE_SMS) && date.equals(today)) {
			queueAbsenceSms(newlyAbsent, date);
		}
		return register(sectionId, date);
	}

	private void queueAbsenceSms(List<Long> studentIds, LocalDate date) {
		for (long studentId : studentIds) {
			var s = jdbc.queryForMap("""
					select st.name, f.father_phone, f.mother_phone, f.preferred_channel,
					       (select c.phone from student_contact c where c.student_id = st.id and c.receives_attendance
					          and c.phone is not null order by c.id limit 1) as contact_phone
					from student st join family f on f.id = st.family_id where st.id = ?""", studentId);
			if ("NONE".equals(s.get("preferred_channel"))) {
				continue;
			}
			String phone = s.get("contact_phone") != null ? (String) s.get("contact_phone")
					: s.get("father_phone") != null ? (String) s.get("father_phone") : (String) s.get("mother_phone");
			if (phone == null) {
				continue;
			}
			outbox.queueSms(phone, templates.render(MessageTemplates.Code.ATTENDANCE_ABSENT, Map.of("name",
					(String) s.get("name"), "date", com.selectcode.sms.shared.pdf.PdfKit.date(date))), "ATTENDANCE_ABSENT",
					"student", studentId, "absent-" + studentId + "-" + date);
		}
	}

	// ------------------------------------------------------------- missing

	/** Sections with students whose register for the day is not taken (FR 13.2). Empty on a closed day. */
	@Transactional(readOnly = true)
	public List<MissingRegister> missing(LocalDate date) {
		if (!calendar.isWorkingDay(date)) {
			return List.of();
		}
		YearRef year = access.yearOf(date);
		var allowed = access.viewable(year, date);
		return jdbc.query("""
				select sec.id, c.name || ' - ' || sec.name, count(e.id),
				       (select st.name from teaching_assignment t join staff st on st.id = t.staff_id
				        where t.section_id = sec.id and t.subject_id is null and t.effective_from <= ?
				          and (t.effective_to is null or t.effective_to >= ?) limit 1)
				from section sec join school_class c on c.id = sec.class_id
				join enrolment e on e.section_id = sec.id and e.effective_from <= ?
				     and (e.effective_to is null or e.effective_to >= ?)
				where not exists (select 1 from register_completion r where r.section_id = sec.id and r.att_date = ?)
				group by sec.id, c.name, sec.name, c.display_order order by c.display_order, sec.name""",
				(rs, i) -> new MissingRegister(rs.getLong(1), rs.getString(2), date, rs.getInt(3), rs.getString(4)),
				Date.valueOf(date), Date.valueOf(date), Date.valueOf(date), Date.valueOf(date), Date.valueOf(date))
			.stream()
			.filter(m -> allowed.isEmpty() || allowed.get().contains(m.sectionId()))
			.toList();
	}

	// ----------------------------------------------------------- corrections

	/** One change for many registers, e.g. "school trip: absent → present" for a class over two days (FR 13.3). */
	@Transactional
	public BulkResult bulk(BulkInput in) {
		String why = PakFormats.trimToNull(in.reason());
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the change");
		}
		if (in.toStatus() == null || !STATUSES.contains(in.toStatus())
				|| (in.fromStatus() != null && !in.fromStatus().isBlank() && !STATUSES.contains(in.fromStatus()))) {
			throw ApiException.businessRule("STATUS_INVALID", "Use P, L, HD, A or LV");
		}
		LocalDate today = MoneyInput.today(clock);
		LocalDate to = in.to().isAfter(today) ? today : in.to();
		if (to.isBefore(in.from()) || ChronoUnit.DAYS.between(in.from(), to) > 62) {
			throw ApiException.businessRule("RANGE_INVALID", "Choose up to two months, not in the future");
		}
		List<Object> filterArgs = new ArrayList<>();
		String filter = "";
		if (in.sectionId() != null) {
			filter = " and e.section_id = ?";
			filterArgs.add(in.sectionId());
		}
		else if (in.classId() != null) {
			filter = " and e.class_id = ?";
			filterArgs.add(in.classId());
		}
		String from = PakFormats.trimToNull(in.fromStatus());
		String actor = CurrentActor.require().displayName();
		long schoolId = TenantContext.requireSchoolId();
		int changed = 0;
		int created = 0;
		List<LocalDate> days = calendar.workingDays(in.from(), to);
		for (LocalDate day : days) {
			YearRef year = access.yearOf(day);
			yearContext.requireWritable(year);
			List<Object> args = new ArrayList<>(List.of(Date.valueOf(day), Date.valueOf(day), Date.valueOf(day)));
			args.addAll(filterArgs);
			List<Object[]> students = jdbc.query("""
					select e.student_id, e.section_id, a.status from enrolment e
					left join student_attendance a on a.student_id = e.student_id and a.att_date = ?
					where e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)""" + filter,
					(rs, i) -> new Object[] { rs.getLong(1), rs.getLong(2), rs.getString(3) }, args.toArray());
			Set<Long> sections = new java.util.HashSet<>();
			for (Object[] s : students) {
				String old = (String) s[2];
				if (old == null) {
					if (!in.fillUnmarked()) {
						continue;
					}
					jdbc.update("""
							insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id,
							                                status, note, marked_by)
							values (?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, year.id(), Date.valueOf(day), s[1], s[0],
							in.toStatus(), why, actor);
					sections.add((Long) s[1]);
					created++;
					continue;
				}
				if (old.equals(in.toStatus()) || (from != null && !from.equals(old))) {
					continue;
				}
				jdbc.update("update student_attendance set status = ?, marked_by = ?, marked_at = now() "
						+ "where student_id = ? and att_date = ?", in.toStatus(), actor, s[0], Date.valueOf(day));
				jdbc.update("""
						insert into attendance_correction (school_id, person_type, person_id, att_date, old_status,
						                                   new_status, reason, corrected_by)
						values (?, 'STUDENT', ?, ?, ?, ?, ?, ?)""", schoolId, s[0], Date.valueOf(day), old, in.toStatus(),
						why, actor);
				changed++;
			}
			for (long sectionId : sections) {
				jdbc.update("""
						insert into register_completion (school_id, section_id, att_date, completed_by) values (?, ?, ?, ?)
						on conflict do nothing""", schoolId, sectionId, Date.valueOf(day), actor);
			}
		}
		audit.record("ATTENDANCE_BULK_CORRECTED", "attendance", null, null,
				Map.of("from", in.from().toString(), "to", to.toString(), "toStatus", in.toStatus(), "changed", changed,
						"created", created), why);
		return new BulkResult(days.size(), changed, created);
	}

	@Transactional(readOnly = true)
	public List<CorrectionRow> corrections(LocalDate from, LocalDate to, String personType, Long personId) {
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
		StringBuilder sql = new StringBuilder("""
				select c.id, c.person_type, c.person_id,
				       case when c.person_type = 'STUDENT' then (select s.name from student s where s.id = c.person_id)
				            else (select st.name from staff st where st.id = c.person_id) end,
				       c.att_date, c.old_status, c.new_status, c.reason, c.corrected_by, c.corrected_at
				from attendance_correction c where c.att_date between ? and ?""");
		if (personType != null && !personType.isBlank()) {
			sql.append(" and c.person_type = ?");
			args.add(personType);
		}
		if (personId != null) {
			sql.append(" and c.person_id = ?");
			args.add(personId);
		}
		sql.append(" order by c.id desc limit 500");
		return jdbc.query(sql.toString(), (rs, i) -> new CorrectionRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
				rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getString(7), rs.getString(8),
				rs.getString(9), rs.getTimestamp(10).toInstant()), args.toArray());
	}
}
