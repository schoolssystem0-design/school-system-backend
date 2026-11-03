package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Defaulter follow-up remarks and fee reminder messages (FR 10.16). */
@Service
public class FeeCollections {

	public record FollowUp(long id, String remark, LocalDate nextDate, String createdBy, Instant createdAt) {
	}

	public record ReminderResult(int queued, int alreadyQueued, int noPhone, int nothingDue) {
	}

	private final JdbcTemplate jdbc;

	private final OpenLines openLines;

	private final MessageOutbox outbox;

	private final MessageTemplates templates;

	private final AuditService audit;

	FeeCollections(JdbcTemplate jdbc, OpenLines openLines, MessageOutbox outbox, MessageTemplates templates, AuditService audit) {
		this.jdbc = jdbc;
		this.openLines = openLines;
		this.outbox = outbox;
		this.templates = templates;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<FollowUp> followUps(long familyId) {
		return jdbc.query("""
				select id, remark, next_date, created_by, created_at from fee_followup where family_id = ?
				order by id desc""", (rs, i) -> new FollowUp(rs.getLong(1), rs.getString(2),
				rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), rs.getString(4),
				rs.getTimestamp(5).toInstant()), familyId);
	}

	@Transactional
	public List<FollowUp> addFollowUp(long familyId, String remark, LocalDate nextDate) {
		String text = PakFormats.trimToNull(remark);
		if (text == null || text.length() > 1000) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Write what was discussed (up to 1,000 characters)");
		}
		Long n = jdbc.queryForObject("select count(*) from family where id = ?", Long.class, familyId);
		if (n == null || n == 0) {
			throw ApiException.notFound("Family");
		}
		jdbc.update("""
				insert into fee_followup (school_id, family_id, remark, next_date, created_by) values (?, ?, ?, ?, ?)""",
				TenantContext.requireSchoolId(), familyId, text, nextDate == null ? null : Date.valueOf(nextDate),
				CurrentActor.require().displayName());
		return followUps(familyId);
	}

	/**
	 * Queues one reminder SMS per family with dues. The same Idempotency-Key never messages a family twice, so a
	 * retried request is safe. {@code message} may contain {name}, {amount} and {school}.
	 */
	@Transactional
	public ReminderResult sendReminders(List<Long> familyIds, String message, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		if (familyIds == null || familyIds.isEmpty()) {
			throw ApiException.businessRule("FAMILIES_REQUIRED", "Choose the families to remind");
		}
		if (familyIds.size() > 2000) {
			throw ApiException.businessRule("TOO_MANY", "Send at most 2,000 reminders at a time");
		}
		String template = PakFormats.trimToNull(message) != null ? message.trim()
				: templates.body(MessageTemplates.Code.FEE_REMINDER);
		if (template.length() > 500) {
			throw ApiException.businessRule("MESSAGE_TOO_LONG", "Keep the message under 500 characters");
		}
		int queued = 0;
		int already = 0;
		int noPhone = 0;
		int nothing = 0;
		for (long familyId : familyIds.stream().distinct().toList()) {
			List<Map<String, Object>> rows = jdbc.queryForList(
					"select father_name, father_phone, mother_phone, preferred_channel from family where id = ?", familyId);
			if (rows.isEmpty()) {
				continue;
			}
			Map<String, Object> f = rows.getFirst();
			BigDecimal due = openLines.owed(familyId).subtract(openLines.creditTotal(familyId));
			if (due.signum() <= 0) {
				nothing++;
				continue;
			}
			String phone = f.get("father_phone") != null ? (String) f.get("father_phone") : (String) f.get("mother_phone");
			if (phone == null || "NONE".equals(f.get("preferred_channel"))) {
				noPhone++;
				continue;
			}
			String body = templates.finish(template, Map.of("name",
					f.get("father_name") == null ? "Parent" : (String) f.get("father_name"), "amount", Amounts.rupees(due)));
			if (outbox.queueSms(phone, body, "FEE_REMINDER", "family", familyId, idempotencyKey.trim())) {
				queued++;
				jdbc.update("""
						insert into fee_followup (school_id, family_id, remark, created_by) values (?, ?, ?, ?)""",
						TenantContext.requireSchoolId(), familyId, "Reminder SMS sent for " + Amounts.rupees(due),
						CurrentActor.require().displayName());
			}
			else {
				already++;
			}
		}
		audit.record("FEE_REMINDERS_SENT", "family", null, null,
				Map.of("queued", queued, "alreadyQueued", already, "noPhone", noPhone));
		return new ReminderResult(queued, already, noPhone, nothing);
	}
}
