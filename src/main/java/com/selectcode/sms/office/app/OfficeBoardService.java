package com.selectcode.sms.office.app;

import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Circulars (FR 3.11), the events calendar (FR 3.10), the visitor log (FR 3.9) and birthdays (FR 3.4). */
@Service
public class OfficeBoardService {

	public record CircularView(long id, String title, String body, LocalDate date, String createdBy) {
	}

	public record CircularInput(String title, String body, LocalDate date) {
	}

	public record EventView(long id, String title, LocalDate dateFrom, LocalDate dateTo, String type, String details,
			boolean showOnWebsite) {
	}

	public record EventInput(String title, LocalDate dateFrom, LocalDate dateTo, String type, String details,
			boolean showOnWebsite) {
	}

	public record VisitorView(long id, LocalDate date, String name, String purpose, LocalTime timeIn, String createdBy) {
	}

	public record VisitorInput(LocalDate date, String name, String purpose, LocalTime timeIn) {
	}

	public record BirthdayRow(String personType, long id, String name, String detail, LocalDate birthday, long daysLeft,
			Long photoFileId, boolean wished) {
	}

	public record WishResult(boolean queued, boolean alreadySent, boolean noPhone) {
	}

	private final JdbcTemplate jdbc;

	private final MessageTemplates templates;

	private final MessageOutbox outbox;

	private final AuditService audit;

	private final Clock clock;

	OfficeBoardService(JdbcTemplate jdbc, MessageTemplates templates, MessageOutbox outbox, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.templates = templates;
		this.outbox = outbox;
		this.audit = audit;
		this.clock = clock;
	}

	private static String required(String value, String message) {
		String v = PakFormats.trimToNull(value);
		if (v == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", message);
		}
		return v;
	}

	// ------------------------------------------------------------- circulars

	@Transactional(readOnly = true)
	public List<CircularView> circulars(int limit) {
		return jdbc.query("""
				select id, title, body, circular_date, created_by from circular
				order by circular_date desc, id desc limit ?""", (rs, i) -> new CircularView(rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5)), Math.min(Math.max(limit, 1), 200));
	}

	@Transactional
	public CircularView postCircular(CircularInput in) {
		String title = required(in.title(), "Enter the title");
		String body = required(in.body(), "Write the circular");
		LocalDate date = in.date() == null ? MoneyInput.today(clock) : in.date();
		Long id = jdbc.queryForObject("""
				insert into circular (school_id, title, body, circular_date, created_by) values (?, ?, ?, ?, ?) returning id""",
				Long.class, TenantContext.requireSchoolId(), title, body, Date.valueOf(date),
				CurrentActor.require().displayName());
		audit.record("CIRCULAR_POSTED", "circular", id, null, Map.of("title", title));
		return new CircularView(id, title, body, date, CurrentActor.require().displayName());
	}

	@Transactional
	public void removeCircular(long id) {
		List<String> title = jdbc.queryForList("select title from circular where id = ?", String.class, id);
		if (title.isEmpty()) {
			throw ApiException.notFound("Circular");
		}
		jdbc.update("delete from circular where id = ?", id);
		audit.record("CIRCULAR_REMOVED", "circular", id, Map.of("title", title.getFirst()), null);
	}

	// ---------------------------------------------------------------- events

	/** Events overlapping a period, for the month calendar. */
	@Transactional(readOnly = true)
	public List<EventView> events(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select id, title, date_from, date_to, event_type, details, show_on_website from school_event
				where date_from <= ? and date_to >= ? order by date_from, id""", (rs, i) -> new EventView(rs.getLong(1),
				rs.getString(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6),
				rs.getBoolean(7)), Date.valueOf(to), Date.valueOf(from));
	}

	@Transactional
	public EventView addEvent(EventInput in) {
		String title = required(in.title(), "Enter the event title");
		if (in.dateFrom() == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose the date");
		}
		LocalDate to = in.dateTo() == null ? in.dateFrom() : in.dateTo();
		if (to.isBefore(in.dateFrom())) {
			throw ApiException.businessRule("DATES_INVALID", "The last day is before the first day");
		}
		String type = in.type() == null ? "EVENT" : in.type();
		if (!List.of("EVENT", "HOLIDAY", "EXAM", "OTHER").contains(type)) {
			throw ApiException.businessRule("EVENT_TYPE_INVALID", "Choose school event, holiday, examination or other");
		}
		String details = PakFormats.trimToNull(in.details());
		Long id = jdbc.queryForObject("""
				insert into school_event (school_id, title, date_from, date_to, event_type, details, show_on_website, created_by)
				values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), title,
				Date.valueOf(in.dateFrom()), Date.valueOf(to), type, details, in.showOnWebsite(),
				CurrentActor.require().displayName());
		audit.record("EVENT_ADDED", "school_event", id, null, Map.of("title", title));
		return new EventView(id, title, in.dateFrom(), to, type, details, in.showOnWebsite());
	}

	@Transactional
	public void removeEvent(long id) {
		List<String> title = jdbc.queryForList("select title from school_event where id = ?", String.class, id);
		if (title.isEmpty()) {
			throw ApiException.notFound("Event");
		}
		jdbc.update("delete from school_event where id = ?", id);
		audit.record("EVENT_REMOVED", "school_event", id, Map.of("title", title.getFirst()), null);
	}

	// -------------------------------------------------------------- visitors

	@Transactional(readOnly = true)
	public List<VisitorView> visitors(LocalDate date) {
		return jdbc.query("""
				select id, visit_date, name, purpose, time_in, created_by from visitor_entry where visit_date = ?
				order by time_in nulls last, id""", (rs, i) -> new VisitorView(rs.getLong(1), rs.getDate(2).toLocalDate(),
				rs.getString(3), rs.getString(4), rs.getTime(5) == null ? null : rs.getTime(5).toLocalTime(), rs.getString(6)),
				Date.valueOf(date));
	}

	@Transactional
	public VisitorView addVisitor(VisitorInput in) {
		String name = required(in.name(), "Enter the visitor's name");
		LocalDate today = MoneyInput.today(clock);
		LocalDate date = in.date() == null ? today : in.date();
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "The visit date cannot be in the future");
		}
		String purpose = PakFormats.trimToNull(in.purpose());
		String by = CurrentActor.require().displayName();
		Long id = jdbc.queryForObject("""
				insert into visitor_entry (school_id, visit_date, name, purpose, time_in, created_by)
				values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), Date.valueOf(date), name,
				purpose, in.timeIn() == null ? null : Time.valueOf(in.timeIn()), by);
		return new VisitorView(id, date, name, purpose, in.timeIn(), by);
	}

	// ------------------------------------------------------------- birthdays

	private static String wishKey(String type, long id, int year) {
		return "birthday-" + type + "-" + id + "-" + year;
	}

	/** Birthdays of active students and staff in the coming days (today = 0). 29 February counts as 28 February. */
	@Transactional(readOnly = true)
	public List<BirthdayRow> birthdays(int days) {
		LocalDate today = MoneyInput.today(clock);
		Set<String> sent = new HashSet<>(jdbc.queryForList("""
				select idempotency_key from message_outbox where purpose = 'BIRTHDAY' and created_at >= ?""", String.class,
				java.sql.Timestamp.from(clock.instant().minus(400, ChronoUnit.DAYS))));
		List<BirthdayRow> rows = new ArrayList<>();
		jdbc.query("""
				select s.id, s.name, s.dob, s.photo_file_id, (select c.name || ' - ' || se.name from enrolment e
				          join section se on se.id = e.section_id join school_class c on c.id = e.class_id
				          where e.student_id = s.id order by e.effective_from desc limit 1)
				from student s where s.status = 'ACTIVE' and s.dob is not null""", rs -> {
			add(rows, sent, "STUDENT", rs.getLong(1), rs.getString(2), rs.getString(5), rs.getDate(3).toLocalDate(),
					(Long) rs.getObject(4), today, days);
		});
		jdbc.query("""
				select id, name, dob, designation from staff where status = 'ACTIVE' and dob is not null""", rs -> {
			add(rows, sent, "STAFF", rs.getLong(1), rs.getString(2), rs.getString(4), rs.getDate(3).toLocalDate(), null, today,
					days);
		});
		rows.sort(Comparator.comparingLong(BirthdayRow::daysLeft).thenComparing(BirthdayRow::name));
		return rows;
	}

	private static void add(List<BirthdayRow> rows, Set<String> sent, String type, long id, String name, String detail,
			LocalDate dob, Long photo, LocalDate today, int days) {
		LocalDate next = dob.withYear(today.getYear());
		if (next.isBefore(today)) {
			next = dob.withYear(today.getYear() + 1);
		}
		long left = ChronoUnit.DAYS.between(today, next);
		if (left <= days) {
			rows.add(new BirthdayRow(type, id, name, detail, next, left, photo, sent.contains(wishKey(type, id, next.getYear()))));
		}
	}

	/** Sends a birthday wish, at most once a year per person. */
	@Transactional
	public WishResult wish(String personType, long id) {
		Map<String, Object> person;
		if ("STUDENT".equals(personType)) {
			person = jdbc.queryForList("""
					select s.name, coalesce(f.father_phone, f.mother_phone) as phone, f.preferred_channel, s.dob
					from student s join family f on f.id = s.family_id where s.id = ?""", id).stream().findFirst()
				.orElseThrow(() -> ApiException.notFound("Student"));
		}
		else if ("STAFF".equals(personType)) {
			person = jdbc.queryForList("select name, phone, 'SMS' as preferred_channel, dob from staff where id = ?", id).stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Staff member"));
		}
		else {
			throw ApiException.businessRule("PERSON_TYPE_INVALID", "Choose a student or a staff member");
		}
		String phone = (String) person.get("phone");
		if (phone == null || "NONE".equals(person.get("preferred_channel"))) {
			return new WishResult(false, false, true);
		}
		int year = MoneyInput.today(clock).getYear();
		boolean queued = outbox.queueSms(phone, templates.render(MessageTemplates.Code.BIRTHDAY, Map.of("name",
				(String) person.get("name"))), "BIRTHDAY", personType.toLowerCase(), id, wishKey(personType, id, year));
		return new WishResult(queued, !queued, false);
	}
}
