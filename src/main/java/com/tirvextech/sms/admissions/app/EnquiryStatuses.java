package com.selectcode.sms.admissions.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.events.SchoolCreatedEvent;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enquiry statuses with colours (FR 4.2). The five standard ones carry a system code used by the workflow; schools
 * may rename them, change colours and add their own.
 */
@Service
public class EnquiryStatuses {

	public static final String NEW = "NEW";

	public static final String FOLLOW_UP = "FOLLOW_UP";

	public static final String APPROVED = "APPROVED";

	public static final String NOT_PROCEEDING = "NOT_PROCEEDING";

	public static final String ENROLLED = "ENROLLED";

	public record EnquiryStatus(long id, String name, String colour, int displayOrder, String systemCode, boolean active) {
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	EnquiryStatuses(JdbcTemplate jdbc, AuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/** Seeds the standard statuses in a new school (same transaction as its creation). */
	@EventListener
	public void onSchoolCreated(SchoolCreatedEvent event) {
		Object[][] defaults = { { "New enquiry", "#2563eb", 1, NEW }, { "Follow-up needed", "#d97706", 2, FOLLOW_UP },
				{ "Admission approved", "#16a34a", 3, APPROVED }, { "Not proceeding", "#6b7280", 4, NOT_PROCEEDING },
				{ "Enrolled", "#7c3aed", 5, ENROLLED } };
		for (Object[] d : defaults) {
			jdbc.update("""
					insert into enquiry_status (school_id, name, colour, display_order, system_code)
					values (?, ?, ?, ?, ?)""", event.schoolId(), d[0], d[1], d[2], d[3]);
		}
	}

	@Transactional(readOnly = true)
	public List<EnquiryStatus> list() {
		return jdbc.query("""
				select id, name, colour, display_order, system_code, active from enquiry_status
				order by display_order, name""", (rs, i) -> new EnquiryStatus(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getInt(4), rs.getString(5), rs.getBoolean(6)));
	}

	public EnquiryStatus require(long id) {
		return list().stream().filter(s -> s.id() == id).findFirst().orElseThrow(() -> ApiException.notFound("Enquiry status"));
	}

	public EnquiryStatus bySystemCode(String code) {
		return list().stream()
			.filter(s -> code.equals(s.systemCode()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Missing enquiry status " + code));
	}

	@Transactional
	public EnquiryStatus create(String name, String colour) {
		String clean = PakFormats.trimToNull(name);
		if (clean == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name is required");
		}
		int order = list().stream().mapToInt(EnquiryStatus::displayOrder).max().orElse(0) + 1;
		Long id = jdbc.queryForObject("""
				insert into enquiry_status (school_id, name, colour, display_order) values (?, ?, ?, ?) returning id""",
				Long.class, TenantContext.requireSchoolId(), clean, colour(colour), order);
		audit.record("ENQUIRY_STATUS_CREATED", "enquiry_status", id, null, Map.of("name", clean));
		return require(id);
	}

	@Transactional
	public EnquiryStatus update(long id, String name, String colour, boolean active) {
		EnquiryStatus current = require(id);
		String clean = PakFormats.trimToNull(name);
		if (clean == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name is required");
		}
		if (!active && current.systemCode() != null) {
			throw ApiException.conflict("STATUS_REQUIRED", "Standard statuses cannot be switched off; rename them instead");
		}
		jdbc.update("update enquiry_status set name = ?, colour = ?, active = ? where id = ?", clean, colour(colour),
				active, id);
		audit.record("ENQUIRY_STATUS_UPDATED", "enquiry_status", id, current, Map.of("name", clean, "active", active));
		return require(id);
	}

	private static String colour(String value) {
		String c = value == null ? "#64748b" : value.trim();
		if (!c.matches("#[0-9a-fA-F]{6}")) {
			throw ApiException.businessRule("COLOUR_INVALID", "Use a colour like #2563eb");
		}
		return c;
	}
}
