package com.selectcode.sms.dashboard.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.attendance.RepeatedAbsenceCount;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard (FR 3). Each panel is built only if the user's permissions and data scope allow it; a panel not allowed
 * is absent from the response (QA-DSH-003). Teachers see attendance of their own sections only. When the school masks
 * figures, totals and fee figures stay off the response until the user asks to reveal them (QA-DSH-004).
 */
@Service
public class DashboardService {

	/** At most this many urgent lines (FR 3.13, QA-DSH-002). */
	private static final int MAX_URGENT = 5;

	public record Totals(long students, long boys, long girls, long families, long staff) {
	}

	public record FeeFigures(String collectedToday, String collectedThisMonth, String outstanding) {
	}

	public record DayFigure(LocalDate date, int present, int absent, int leave, int late) {
	}

	public record AttendancePanel(boolean workingDay, int sections, int completed, List<String> missing, DayFigure students,
			DayFigure staff, List<DayFigure> lastSevenDays, Integer repeatedAbsences) {
	}

	public record ComplaintCounts(int open, int closed, int total, int urgent) {
	}

	public record BirthdayLine(String personType, long id, String name, String detail, LocalDate birthday, long daysLeft,
			Long photoFileId) {
	}

	public record TaskLine(long id, String description, LocalDate deadline, String priority, boolean overdue) {
	}

	public record RegisterLine(long studentId, String grNo, String name, String className, String kind, LocalDate date,
			long daysAgo, boolean welcomeSent) {
	}

	public record LoginGroup(String userType, int logins) {
	}

	public record LocationAlert(String username, Instant at) {
	}

	public record LoginPanel(List<LoginGroup> byType, int failed, List<LocationAlert> newLocations) {
	}

	public record EventLine(long id, String title, LocalDate dateFrom, LocalDate dateTo, String type) {
	}

	public record CircularLine(long id, String title, LocalDate date) {
	}

	public record UrgentLine(String code, String text, String href) {
	}

	public record Favourite(String href, String label) {
	}

	public record Dashboard(boolean masked, List<UrgentLine> urgent, Totals totals, FeeFigures fees, AttendancePanel attendance,
			ComplaintCounts complaints, List<BirthdayLine> birthdays, List<TaskLine> tasks, List<RegisterLine> register,
			LoginPanel logins, Integer visitorsToday, List<EventLine> events, List<CircularLine> circulars,
			List<Favourite> favourites) {
	}

	private final JdbcTemplate jdbc;

	private final YearContext yearContext;

	private final AcademicStructure structure;

	private final SchoolUserAdministration users;

	private final WorkingDayCalendar calendar;

	private final RepeatedAbsenceCount repeated;

	private final SettingsService settings;

	private final Clock clock;

	DashboardService(JdbcTemplate jdbc, YearContext yearContext, AcademicStructure structure, SchoolUserAdministration users,
			WorkingDayCalendar calendar, RepeatedAbsenceCount repeated, SettingsService settings, Clock clock) {
		this.jdbc = jdbc;
		this.yearContext = yearContext;
		this.structure = structure;
		this.users = users;
		this.calendar = calendar;
		this.repeated = repeated;
		this.settings = settings;
		this.clock = clock;
	}

	private static boolean can(String permission) {
		return CurrentActor.hasPermission(permission);
	}

	private static boolean all() {
		return DataScope.current() == DataScope.ALL;
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard(boolean reveal) {
		LocalDate today = MoneyInput.today(clock);
		YearRef year = yearContext.selected().orElse(null);
		boolean masked = settings.bool(SettingKey.MASK_DASHBOARD_FIGURES) && !reveal;
		boolean office = all();
		AttendancePanel attendance = can(Permissions.ATTENDANCE_VIEW) && year != null ? attendance(year, today) : null;
		return new Dashboard(masked, urgent(attendance, today),
				!masked && office && can(Permissions.STUDENTS_VIEW) && year != null ? totals(year) : null,
				!masked && office && can(Permissions.FEES_REPORTS_VIEW) ? fees(today) : null, attendance,
				office && can(Permissions.OFFICE_COMPLAINTS_VIEW) && year != null ? complaints(year) : null,
				office && can(Permissions.STUDENTS_VIEW) ? birthdays(today) : null,
				can(Permissions.OFFICE_TASKS) ? tasks(today) : null,
				office && can(Permissions.STUDENTS_VIEW) && year != null ? register(year, today) : null,
				office && can(Permissions.IAM_LOGIN_HISTORY_VIEW) ? logins() : null,
				office && can(Permissions.OFFICE_VISITORS_VIEW)
						? jdbc.queryForObject("select count(*) from visitor_entry where visit_date = ?", Integer.class,
								Date.valueOf(today))
						: null,
				can(Permissions.OFFICE_EVENTS_VIEW) ? events(today) : null,
				can(Permissions.OFFICE_CIRCULARS_VIEW) ? circulars() : null, favourites());
	}

	private Totals totals(YearRef year) {
		return jdbc.queryForObject("""
				select count(distinct s.id), count(distinct s.id) filter (where s.gender = 'MALE'),
				       count(distinct s.id) filter (where s.gender = 'FEMALE'), count(distinct s.family_id),
				       (select count(*) from staff where status = 'ACTIVE')
				from student s join enrolment e on e.student_id = s.id
				where s.status = 'ACTIVE' and e.academic_year_id = ?""",
				(rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), year.id());
	}

	private FeeFigures fees(LocalDate today) {
		BigDecimal day = jdbc.queryForObject("select coalesce(sum(amount), 0) from payment where payment_date = ?",
				BigDecimal.class, Date.valueOf(today));
		BigDecimal month = jdbc.queryForObject(
				"select coalesce(sum(amount), 0) from payment where payment_date between ? and ?", BigDecimal.class,
				Date.valueOf(today.withDayOfMonth(1)), Date.valueOf(today));
		BigDecimal due = jdbc.queryForObject("select coalesce(sum(balance), 0) from fee_line_status where balance > 0",
				BigDecimal.class);
		return new FeeFigures(day.toPlainString(), month.toPlainString(), due.toPlainString());
	}

	/** Sections the user sees attendance of today, or null for all. */
	private Set<Long> allowedSections(YearRef year, LocalDate today) {
		if (all()) {
			return null;
		}
		var actor = CurrentActor.require();
		Long staff = actor.actorType() == ActorType.USER && DataScope.current() != DataScope.SELF
				? users.staffIdOfUser(actor.actorId()).orElse(null)
				: null;
		return staff == null ? Set.of() : structure.sectionsTaughtBy(staff, year.id(), today);
	}

	private AttendancePanel attendance(YearRef year, LocalDate today) {
		boolean working = calendar.isWorkingDay(today);
		Set<Long> allowed = allowedSections(year, today);
		Date on = Date.valueOf(today);
		List<Long> sections = jdbc.queryForList("""
				select distinct e.section_id from enrolment e where e.effective_from <= ?
				   and (e.effective_to is null or e.effective_to >= ?)""", Long.class, on, on)
			.stream()
			.filter(s -> allowed == null || allowed.contains(s))
			.toList();
		Set<Long> done = Set.copyOf(jdbc.queryForList("select section_id from register_completion where att_date = ?", Long.class, on));
		List<String> missing = working
				? sections.stream()
					.filter(s -> !done.contains(s))
					.map(s -> structure.findSection(s).map(AcademicStructure.SectionRef::label).orElse(""))
					.sorted()
					.toList()
				: List.of();
		String sectionFilter = allowed == null ? "" : " and section_id = any (array[" + joined(allowed) + "]::bigint[])";
		List<DayFigure> week = new ArrayList<>(jdbc.query("""
				select att_date, count(*) filter (where status in ('P', 'L', 'HD')), count(*) filter (where status = 'A'),
				       count(*) filter (where status = 'LV'), count(*) filter (where status = 'L')
				from student_attendance where att_date between ? and ?""" + sectionFilter + " group by att_date order by att_date",
				(rs, i) -> new DayFigure(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
				Date.valueOf(today.minusDays(6)), on));
		DayFigure students = week.stream().filter(d -> d.date().equals(today)).findFirst().orElse(new DayFigure(today, 0, 0, 0, 0));
		DayFigure staff = null;
		if (all()) {
			staff = jdbc.queryForObject("""
					select count(*) filter (where status in ('P', 'L', 'HD')), count(*) filter (where status = 'A'),
					       count(*) filter (where status = 'LV'), count(*) filter (where status = 'L')
					from staff_attendance where att_date = ?""", (rs, i) -> new DayFigure(today, rs.getInt(1), rs.getInt(2),
					rs.getInt(3), rs.getInt(4)), on);
		}
		return new AttendancePanel(working, sections.size(), (int) sections.stream().filter(done::contains).count(), missing,
				students, staff, week, all() ? repeated.repeatedAbsenceCount(today) : null);
	}

	private static String joined(Set<Long> ids) {
		return ids.isEmpty() ? "null" : String.join(",", ids.stream().map(String::valueOf).toList());
	}

	private ComplaintCounts complaints(YearRef year) {
		Timestamp cutoff = Timestamp
			.from(clock.instant().minus(settings.integer(SettingKey.COMPLAINT_URGENT_DAYS), ChronoUnit.DAYS));
		return jdbc.queryForObject("""
				select count(*) filter (where status = 'OPEN'), count(*) filter (where status = 'CLOSED'), count(*),
				       count(*) filter (where status = 'OPEN' and opened_at <= ?)
				from complaint where academic_year_id = ?""",
				(rs, i) -> new ComplaintCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), cutoff, year.id());
	}

	private List<BirthdayLine> birthdays(LocalDate today) {
		// Birthdays in the next 7 days, across a year end, and 29 February taken as 28 February in other years
		List<BirthdayLine> rows = new ArrayList<>();
		jdbc.query("""
				select 'STUDENT', s.id, s.name, (select c.name from enrolment e join school_class c on c.id = e.class_id
				         where e.student_id = s.id order by e.effective_from desc limit 1), s.dob, s.photo_file_id
				from student s where s.status = 'ACTIVE' and s.dob is not null
				union all
				select 'STAFF', t.id, t.name, t.designation, t.dob, null from staff t where t.status = 'ACTIVE' and t.dob is not null""",
				rs -> {
					LocalDate dob = rs.getDate(5).toLocalDate();
					LocalDate next = dob.withYear(today.getYear());
					if (next.isBefore(today)) {
						next = dob.withYear(today.getYear() + 1);
					}
					long left = ChronoUnit.DAYS.between(today, next);
					if (left <= 7) {
						rows.add(new BirthdayLine(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), next, left,
								(Long) rs.getObject(6)));
					}
				});
		rows.sort(java.util.Comparator.comparingLong(BirthdayLine::daysLeft).thenComparing(BirthdayLine::name));
		return rows.stream().limit(8).toList();
	}

	/** SQL condition: the task is the current user's, as creator or through a share. */
	private static String myTasks() {
		var actor = CurrentActor.require();
		if (CurrentActor.isSuperAdmin() || actor.actorType() != ActorType.USER) {
			return " true ";
		}
		long me = actor.actorId();
		return " (t.created_by_user = " + me + " or exists (select 1 from task_share s where s.task_id = t.id and s.user_id = "
				+ me + ")) ";
	}

	private List<TaskLine> tasks(LocalDate today) {
		return jdbc.query("select t.id, t.description, t.deadline, t.priority from task t where t.status = 'OPEN' and " + myTasks()
				+ " order by case t.priority when 'URGENT' then 0 else 1 end, t.deadline nulls last, t.id desc limit 6", (rs, i) -> {
					LocalDate deadline = rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate();
					return new TaskLine(rs.getLong(1), rs.getString(2), deadline, rs.getString(4),
							deadline != null && deadline.isBefore(today));
				});
	}

	private List<RegisterLine> register(YearRef year, LocalDate today) {
		return jdbc.query("""
				select * from (
				  select s.id, s.gr_no, s.name, (select c.name from enrolment e join school_class c on c.id = e.class_id
				           where e.student_id = s.id and e.academic_year_id = ? order by e.effective_from desc limit 1) as class_name,
				         'ADMITTED' as kind, s.admission_date as on_date,
				         exists (select 1 from message_outbox m where m.idempotency_key = 'welcome-' || s.id) as welcomed
				  from student s where s.admission_date between ? and ?
				  union all
				  select s.id, s.gr_no, s.name, (select c.name from enrolment e join school_class c on c.id = e.class_id
				           where e.student_id = s.id order by e.effective_from desc limit 1),
				         'LEFT', w.leaving_date, false
				  from withdrawal w join student s on s.id = w.student_id where w.academic_year_id = ?
				) r order by r.on_date desc, r.id desc limit 10""",
				(rs, i) -> new RegisterLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
						rs.getDate(6).toLocalDate(), ChronoUnit.DAYS.between(rs.getDate(6).toLocalDate(), today), rs.getBoolean(7)),
				year.id(), Date.valueOf(year.startDate()), Date.valueOf(year.endDate()), year.id());
	}

	private LoginPanel logins() {
		Timestamp since = Timestamp.from(clock.instant().minus(7, ChronoUnit.DAYS));
		List<LoginGroup> groups = jdbc.query("""
				select coalesce((select min(r.name) from user_role ur join role r on r.id = ur.role_id where ur.user_id = e.user_id),
				                'Other') as user_type, count(*)
				from login_event e where e.at >= ? and e.result = 'SUCCESS' group by 1 order by 2 desc""",
				(rs, i) -> new LoginGroup(rs.getString(1), rs.getInt(2)), since);
		Integer failed = jdbc.queryForObject("select count(*) from login_event where at >= ? and result <> 'SUCCESS'",
				Integer.class, since);
		List<LocationAlert> alerts = jdbc.query("""
				select username, at from login_event where at >= ? and new_location order by at desc limit 5""",
				(rs, i) -> new LocationAlert(rs.getString(1), rs.getTimestamp(2).toInstant()), since);
		return new LoginPanel(groups, failed == null ? 0 : failed, alerts);
	}

	private List<EventLine> events(LocalDate today) {
		return jdbc.query("""
				select id, title, date_from, date_to, event_type from school_event where date_to >= ?
				order by date_from, id limit 5""", (rs, i) -> new EventLine(rs.getLong(1), rs.getString(2),
				rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(), rs.getString(5)), Date.valueOf(today));
	}

	private List<CircularLine> circulars() {
		return jdbc.query("""
				select id, title, circular_date from circular order by circular_date desc, id desc limit 5""",
				(rs, i) -> new CircularLine(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate()));
	}

	// ----------------------------------------------------------------- urgent

	/** Items needing action today, most important first (FR 3.13). Nothing when nothing is pending. */
	private List<UrgentLine> urgent(AttendancePanel attendance, LocalDate today) {
		List<UrgentLine> lines = new ArrayList<>();
		if (attendance != null && attendance.workingDay()) {
			List<String> missing = attendance.missing();
			if (missing.size() <= 2) {
				missing.forEach(m -> lines.add(new UrgentLine("ATTENDANCE", m + " attendance not entered", "/app/attendance")));
			}
			else {
				lines.add(new UrgentLine("ATTENDANCE", missing.size() + " classes have attendance not entered", "/app/attendance"));
			}
			if (all() && can(Permissions.ATTENDANCE_STAFF_MARK) && attendance.staff() != null
					&& attendance.staff().present() + attendance.staff().absent() + attendance.staff().leave() == 0
					&& count("select count(*) from staff where status = 'ACTIVE'") > 0) {
				lines.add(new UrgentLine("STAFF_ATTENDANCE", "Staff attendance not entered", "/app/attendance/staff"));
			}
		}
		if (all() && can(Permissions.OFFICE_COMPLAINTS_VIEW)) {
			int n = count("select count(*) from complaint where status = 'OPEN' and opened_at <= ?", Timestamp
				.from(clock.instant().minus(settings.integer(SettingKey.COMPLAINT_URGENT_DAYS), ChronoUnit.DAYS)));
			if (n > 0) {
				lines.add(new UrgentLine("COMPLAINTS", plural(n, "complaint") + " open more than "
						+ settings.integer(SettingKey.COMPLAINT_URGENT_DAYS) + " days", "/app/office/complaints"));
			}
		}
		if (all() && can(Permissions.ADMISSIONS_VIEW)) {
			int days = settings.integer(SettingKey.ENQUIRY_FOLLOWUP_DAYS);
			int n = count("""
					select count(*) from enquiry q join enquiry_status st on st.id = q.status_id
					where st.system_code in ('NEW', 'FOLLOW_UP') and q.enquiry_date <= ?
					  and not exists (select 1 from enquiry_remark r where r.enquiry_id = q.id and r.at >= ?)""",
					Date.valueOf(today.minusDays(days)), Timestamp.from(clock.instant().minus(days, ChronoUnit.DAYS)));
			if (n > 0) {
				lines.add(new UrgentLine("ENQUIRIES", plural(n, "enquiry").replace("enquirys", "enquiries")
						+ " with no follow-up for " + days + " days", "/app/admissions"));
			}
		}
		if (all() && can(Permissions.FEES_VIEW)) {
			int n = count("""
					select count(*) from (select distinct on (family_id) family_id, next_date from fee_followup
					                      order by family_id, id desc) f where f.next_date < ?""", Date.valueOf(today));
			if (n > 0) {
				lines.add(new UrgentLine("FEE_FOLLOWUPS", plural(n, "fee follow-up") + " overdue", "/app/fees/defaulters"));
			}
		}
		if (can(Permissions.OFFICE_TASKS)) {
			int n = count("select count(*) from task t where t.status = 'OPEN' and t.deadline < ? and " + myTasks(), Date.valueOf(today));
			if (n > 0) {
				lines.add(new UrgentLine("TASKS", plural(n, "task") + " overdue", "/app/office/tasks"));
			}
		}
		return lines.stream().limit(MAX_URGENT).toList();
	}

	private int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}

	private static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	// ------------------------------------------------------------- favourites

	public List<Favourite> favourites() {
		var actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			return List.of();
		}
		return jdbc.query("select href, label from user_favourite where user_id = ? order by display_order",
				(rs, i) -> new Favourite(rs.getString(1), rs.getString(2)), actor.actorId());
	}

	/** Saves the user's own quick bar (FR 3.12). Only pages inside the school portal can be pinned. */
	@Transactional
	public List<Favourite> saveFavourites(List<Favourite> pages) {
		var actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			throw ApiException.businessRule("NOT_A_SCHOOL_USER", "The quick bar belongs to school users");
		}
		if (pages.size() > 12) {
			throw ApiException.businessRule("TOO_MANY", "Pin at most 12 pages");
		}
		Map<String, String> unique = new LinkedHashMap<>();
		for (Favourite f : pages) {
			if (f.href() == null || !f.href().startsWith("/app") || f.href().contains("//") || f.label() == null
					|| f.label().isBlank()) {
				throw ApiException.businessRule("PAGE_INVALID", "Only pages of the school portal can be pinned");
			}
			unique.putIfAbsent(f.href(), f.label().trim());
		}
		jdbc.update("delete from user_favourite where user_id = ?", actor.actorId());
		int order = 0;
		for (var e : unique.entrySet()) {
			jdbc.update("""
					insert into user_favourite (school_id, user_id, href, label, display_order) values (?, ?, ?, ?, ?)""",
					TenantContext.requireSchoolId(), actor.actorId(), e.getKey(), e.getValue(), order++);
		}
		return favourites();
	}
}
