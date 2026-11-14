package com.selectcode.sms.office.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parent complaints (FR 3.3): open, assign, keep a remark history, close. Kept per academic year. */
@Service
public class ComplaintService {

	public record ComplaintInput(Long familyId, String complainant, String phone, String subject, String details) {
	}

	public record RemarkView(String text, String createdBy, Instant createdAt) {
	}

	public record ComplaintView(long id, Long familyId, String complainant, String phone, String subject, String details,
			String status, Long assignedToUser, String assignedTo, String openedBy, Instant openedAt, String closedBy,
			Instant closedAt, long daysOpen, boolean urgent, List<RemarkView> remarks) {
	}

	private final JdbcTemplate jdbc;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final SettingsService settings;

	private final AuditService audit;

	private final Clock clock;

	ComplaintService(JdbcTemplate jdbc, YearContext yearContext, AcademicYearLookup years, SettingsService settings,
			AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.yearContext = yearContext;
		this.years = years;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	private static final String SELECT = """
			select c.id, c.family_id, c.complainant, c.phone, c.subject, c.details, c.status, c.assigned_to_user,
			       u.display_name, c.opened_by, c.opened_at, c.closed_by, c.closed_at
			from complaint c left join app_user u on u.id = c.assigned_to_user
			""";

	private ComplaintView map(ResultSet rs, boolean withRemarks) throws SQLException {
		long id = rs.getLong(1);
		Instant opened = rs.getTimestamp(11).toInstant();
		Instant closed = rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant();
		String status = rs.getString(7);
		long days = Duration.between(opened, closed == null ? clock.instant() : closed).toDays();
		return new ComplaintView(id, (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), status, (Long) rs.getObject(8), rs.getString(9), rs.getString(10), opened,
				rs.getString(12), closed, days,
				"OPEN".equals(status) && days >= settings.integer(SettingKey.COMPLAINT_URGENT_DAYS),
				withRemarks ? remarks(id) : List.of());
	}

	private List<RemarkView> remarks(long id) {
		return jdbc.query("""
				select text, created_by, created_at from complaint_remark where complaint_id = ? order by id""",
				(rs, i) -> new RemarkView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), id);
	}

	/** Complaints of the selected year, oldest open first. */
	@Transactional(readOnly = true)
	public List<ComplaintView> list(String status) {
		long year = yearContext.requireSelected().id();
		List<Object> args = new ArrayList<>(List.of(year));
		String filter = "";
		if (status != null && !status.isBlank()) {
			filter = " and c.status = ?";
			args.add(status);
		}
		return jdbc.query(SELECT + " where c.academic_year_id = ?" + filter
				+ " order by case c.status when 'OPEN' then 0 else 1 end, c.opened_at", (rs, i) -> map(rs, false),
				args.toArray());
	}

	@Transactional(readOnly = true)
	public ComplaintView get(long id) {
		return jdbc.query(SELECT + " where c.id = ?", (rs, i) -> map(rs, true), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Complaint"));
	}

	@Transactional
	public ComplaintView create(ComplaintInput in) {
		YearRef year = yearContext.requireWritable();
		String complainant = PakFormats.trimToNull(in.complainant());
		String subject = PakFormats.trimToNull(in.subject());
		if (complainant == null || subject == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter who is complaining and what about");
		}
		if (in.familyId() != null
				&& jdbc.queryForList("select id from family where id = ?", Long.class, in.familyId()).isEmpty()) {
			throw ApiException.notFound("Family");
		}
		Long id = jdbc.queryForObject("""
				insert into complaint (school_id, academic_year_id, family_id, complainant, phone, subject, details, opened_by)
				values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), year.id(),
				in.familyId(), complainant, PakFormats.trimToNull(in.phone()), subject, PakFormats.trimToNull(in.details()),
				CurrentActor.require().displayName());
		audit.record("COMPLAINT_OPENED", "complaint", id, null, Map.of("subject", subject));
		return get(id);
	}

	private void requireOpenYear(long id) {
		Long yearId = jdbc.queryForList("select academic_year_id from complaint where id = ?", Long.class, id).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Complaint"));
		yearContext.requireWritable(years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
	}

	private void remark(long id, String text) {
		jdbc.update("insert into complaint_remark (school_id, complaint_id, text, created_by) values (?, ?, ?, ?)",
				TenantContext.requireSchoolId(), id, text, CurrentActor.require().displayName());
	}

	@Transactional
	public ComplaintView addRemark(long id, String text) {
		requireOpenYear(id);
		String t = PakFormats.trimToNull(text);
		if (t == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Write the remark");
		}
		remark(id, t);
		return get(id);
	}

	@Transactional
	public ComplaintView assign(long id, Long userId) {
		requireOpenYear(id);
		String name = null;
		if (userId != null) {
			name = jdbc.queryForList("select display_name from app_user where id = ? and status = 'ACTIVE'", String.class, userId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Colleague"));
		}
		jdbc.update("update complaint set assigned_to_user = ? where id = ?", userId, id);
		remark(id, name == null ? "Assignment removed" : "Assigned to " + name);
		audit.record("COMPLAINT_ASSIGNED", "complaint", id, null, Map.of("assigned", name == null ? "" : name));
		return get(id);
	}

	/** Closing needs a remark saying how it was resolved; reopening needs one saying why. */
	@Transactional
	public ComplaintView setClosed(long id, boolean closed, String text) {
		requireOpenYear(id);
		String t = PakFormats.trimToNull(text);
		if (t == null) {
			throw ApiException.businessRule("REASON_REQUIRED",
					closed ? "Say how the complaint was resolved" : "Say why the complaint is reopened");
		}
		ComplaintView now = get(id);
		if (closed == "CLOSED".equals(now.status())) {
			throw ApiException.conflict("ALREADY_" + now.status(), "This complaint is already " + now.status().toLowerCase());
		}
		jdbc.update("update complaint set status = ?, closed_by = ?, closed_at = ? where id = ?", closed ? "CLOSED" : "OPEN",
				closed ? CurrentActor.require().displayName() : null, closed ? Timestamp.from(clock.instant()) : null, id);
		remark(id, (closed ? "Closed: " : "Reopened: ") + t);
		audit.record(closed ? "COMPLAINT_CLOSED" : "COMPLAINT_REOPENED", "complaint", id, null, Map.of("status", closed), t);
		return get(id);
	}
}
