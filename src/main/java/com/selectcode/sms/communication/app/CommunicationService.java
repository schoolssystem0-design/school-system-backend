package com.selectcode.sms.communication.app;

import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.communication.MessageTemplates.Code;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.messaging.SmsSegments;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages to families, enquiries and staff (FR 8.6, 4.3, 4.12): bulk sending with a preview of recipients, parts and
 * cost before anything goes; template wording; the delivery log. A phone number gets one message per send, so brothers
 * and sisters do not cost twice. Everything goes through the outbox (D-35).
 */
@Service
public class CommunicationService {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

	private static final int MAX_RECIPIENTS = 5000;

	public record Audience(String type, Long classId, Long sectionId, List<Long> studentIds) {
	}

	public record RecipientRow(String name, String phone) {
	}

	public record SendPreview(int recipients, int noPhone, List<RecipientRow> sample, String sampleMessage, String encoding,
			int characters, int parts, int totalParts, String estimatedCost) {
	}

	public record SendReport(int queued, int alreadyQueued, int noPhone) {
	}

	public record TemplateView(String code, String label, String body, String defaultBody, boolean customised,
			List<String> placeholders, int parts) {
	}

	public record WelcomeResult(boolean queued, boolean alreadySent, boolean noPhone) {
	}

	public record DeliveryLog(List<MessageOutbox.StatusCount> last30Days, List<MessageOutbox.Queued> messages) {
	}

	private final JdbcTemplate jdbc;

	private final MessageTemplates templates;

	private final MessageOutbox outbox;

	private final SettingsService settings;

	private final AuditService audit;

	private final Clock clock;

	CommunicationService(JdbcTemplate jdbc, MessageTemplates templates, MessageOutbox outbox, SettingsService settings,
			AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.templates = templates;
		this.outbox = outbox;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	// --------------------------------------------------------------- recipients

	private record Recipients(List<RecipientRow> rows, int noPhone) {
	}

	private Recipients recipients(Audience a) {
		String type = a.type() == null ? "" : a.type();
		LocalDate today = MoneyInput.today(clock);
		List<Object> args = new ArrayList<>();
		String sql;
		switch (type) {
			case "ALL_FAMILIES", "CLASS", "SECTION", "STUDENTS" -> {
				sql = """
						select distinct on (f.id) coalesce(f.father_name, 'Parent'), coalesce(f.father_phone, f.mother_phone),
						       f.preferred_channel
						from student s join family f on f.id = s.family_id
						join enrolment e on e.student_id = s.id and e.effective_from <= ?
						     and (e.effective_to is null or e.effective_to >= ?)
						where s.status = 'ACTIVE'""";
				args.add(Date.valueOf(today));
				args.add(Date.valueOf(today));
				if ("CLASS".equals(type)) {
					if (a.classId() == null) {
						throw ApiException.businessRule("FIELD_REQUIRED", "Choose the class");
					}
					sql += " and e.class_id = ?";
					args.add(a.classId());
				}
				else if ("SECTION".equals(type)) {
					if (a.sectionId() == null) {
						throw ApiException.businessRule("FIELD_REQUIRED", "Choose the section");
					}
					sql += " and e.section_id = ?";
					args.add(a.sectionId());
				}
				else if ("STUDENTS".equals(type)) {
					if (a.studentIds() == null || a.studentIds().isEmpty()) {
						throw ApiException.businessRule("FIELD_REQUIRED", "Choose the students");
					}
					sql += " and s.id = any (?)";
					args.add(a.studentIds().toArray(new Long[0]));
				}
				sql += " order by f.id";
			}
			case "ENQUIRIES" -> {
				sql = """
						select coalesce(q.parent_name, 'Parent'), q.parent_phone, 'SMS' from enquiry q
						join enquiry_status st on st.id = q.status_id
						where coalesce(st.system_code, '') not in ('ENROLLED', 'NOT_PROCEEDING')""";
				if (a.classId() != null) {
					sql += " and q.class_applied_id = ?";
					args.add(a.classId());
				}
				sql += " order by q.id";
			}
			case "STAFF" -> sql = "select name, phone, 'SMS' from staff where status = 'ACTIVE' order by name";
			default -> throw ApiException.businessRule("AUDIENCE_INVALID", "Choose who receives the message");
		}
		Map<String, RecipientRow> byPhone = new LinkedHashMap<>();
		int[] noPhone = { 0 };
		String query = sql;
		jdbc.query(con -> {
			var ps = con.prepareStatement(query);
			int i = 1;
			for (Object arg : args) {
				if (arg instanceof Long[] ids) {
					ps.setArray(i++, con.createArrayOf("bigint", ids));
				}
				else {
					ps.setObject(i++, arg);
				}
			}
			return ps;
		}, rs -> {
			String phone = rs.getString(2);
			if (phone == null || phone.isBlank() || "NONE".equals(rs.getString(3))) {
				noPhone[0]++;
			}
			else {
				byPhone.putIfAbsent(phone, new RecipientRow(rs.getString(1), phone));
			}
		});
		if (byPhone.size() > MAX_RECIPIENTS) {
			throw ApiException.businessRule("TOO_MANY", "Send to at most 5,000 people at a time");
		}
		return new Recipients(new ArrayList<>(byPhone.values()), noPhone[0]);
	}

	private static String checkedMessage(String message) {
		String text = PakFormats.trimToNull(message);
		if (text == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Write the message");
		}
		if (text.length() > 500) {
			throw ApiException.businessRule("MESSAGE_TOO_LONG", "Keep the message under 500 characters");
		}
		return text;
	}

	/** What a send would do: who gets it, how many SMS parts, what it costs (QA-COM-002). Nothing is queued. */
	@Transactional(readOnly = true)
	public SendPreview preview(Audience audience, String message) {
		String text = checkedMessage(message);
		Recipients r = recipients(audience);
		String sample = templates.finish(text, Map.of("name", r.rows().isEmpty() ? "Parent" : r.rows().getFirst().name()));
		SmsSegments.Count count = SmsSegments.count(sample);
		int total = 0;
		for (RecipientRow row : r.rows()) {
			total += SmsSegments.count(templates.finish(text, Map.of("name", row.name()))).segments();
		}
		BigDecimal cost = settings.money(SettingKey.SMS_COST_PER_PART).amount().multiply(BigDecimal.valueOf(total));
		return new SendPreview(r.rows().size(), r.noPhone(), r.rows().stream().limit(10).toList(), sample, count.encoding(),
				count.characters(), count.segments(), total, cost.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
	}

	/** Queues the message. The same Idempotency-Key never messages a phone twice. */
	@Transactional
	public SendReport send(Audience audience, String message, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		String text = checkedMessage(message);
		Recipients r = recipients(audience);
		int queued = 0;
		int already = 0;
		for (RecipientRow row : r.rows()) {
			if (outbox.queueSms(row.phone(), templates.finish(text, Map.of("name", row.name())), "NOTICE", "audience", null,
					idempotencyKey.trim())) {
				queued++;
			}
			else {
				already++;
			}
		}
		audit.record("MESSAGES_SENT", "message_outbox", null, null,
				Map.of("audience", audience.type(), "queued", queued, "alreadyQueued", already, "noPhone", r.noPhone()));
		return new SendReport(queued, already, r.noPhone());
	}

	// ---------------------------------------------------------------- welcome

	/** The welcome message to a new student's family (FR 4.12). Sent once however often it is pressed. */
	@Transactional
	public WelcomeResult welcome(long studentId) {
		Map<String, Object> s = jdbc.queryForList("""
				select st.name, st.admission_date, coalesce(f.father_phone, f.mother_phone) as phone, f.preferred_channel,
				       (select c.name from enrolment e join school_class c on c.id = e.class_id where e.student_id = st.id
				         order by e.effective_from desc limit 1) as class_name
				from student st join family f on f.id = st.family_id where st.id = ?""", studentId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Student"));
		String phone = (String) s.get("phone");
		if (phone == null || "NONE".equals(s.get("preferred_channel"))) {
			return new WelcomeResult(false, false, true);
		}
		LocalDate first = ((Date) s.get("admission_date")).toLocalDate();
		boolean queued = outbox.queueSms(phone, templates.render(Code.WELCOME, Map.of("name", (String) s.get("name"), "class",
				s.get("class_name") == null ? "our school" : (String) s.get("class_name"), "date",
				com.selectcode.sms.shared.pdf.PdfKit.date(first))), "WELCOME", "student", studentId, "welcome-" + studentId);
		if (queued) {
			audit.record("WELCOME_SENT", "student", studentId, null, Map.of("student", studentId));
		}
		return new WelcomeResult(queued, !queued, false);
	}

	// -------------------------------------------------------------- templates

	@Transactional(readOnly = true)
	public List<TemplateView> templates() {
		List<TemplateView> rows = new ArrayList<>();
		for (Code c : Code.values()) {
			rows.add(view(c));
		}
		return rows;
	}

	private TemplateView view(Code c) {
		String body = templates.body(c);
		return new TemplateView(c.name(), c.label(), body, c.defaultBody(), !body.equals(c.defaultBody()), c.placeholders(),
				SmsSegments.count(body).segments());
	}

	private static Code code(String value) {
		for (Code c : Code.values()) {
			if (c.name().equals(value)) {
				return c;
			}
		}
		throw ApiException.notFound("Template");
	}

	@Transactional
	public TemplateView saveTemplate(String codeName, String body) {
		Code c = code(codeName);
		String text = checkedMessage(body);
		var matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			if (!c.placeholders().contains(matcher.group(1))) {
				throw ApiException.businessRule("PLACEHOLDER_UNKNOWN", "{" + matcher.group(1) + "} cannot be used here. Use: "
						+ String.join(", ", c.placeholders().stream().map(p -> "{" + p + "}").toList()));
			}
		}
		TemplateView before = view(c);
		jdbc.update("""
				insert into message_template (school_id, code, body, updated_by) values (?, ?, ?, ?)
				on conflict (school_id, code) do update set body = excluded.body, updated_by = excluded.updated_by,
				    updated_at = now()""", TenantContext.requireSchoolId(), c.name(), text,
				CurrentActor.require().displayName());
		audit.record("TEMPLATE_SAVED", "message_template", null, Map.of("body", before.body()),
				Map.of("code", c.name(), "body", text));
		return view(c);
	}

	@Transactional
	public TemplateView resetTemplate(String codeName) {
		Code c = code(codeName);
		jdbc.update("delete from message_template where code = ?", c.name());
		audit.record("TEMPLATE_RESET", "message_template", null, null, Map.of("code", c.name()));
		return view(c);
	}

	// --------------------------------------------------------------- delivery

	@Transactional(readOnly = true)
	public DeliveryLog log(String purpose, String status) {
		Instant since = clock.instant().minus(java.time.Duration.ofDays(30));
		return new DeliveryLog(outbox.statusCounts(since), outbox.list(purpose, status, 200));
	}

	@Transactional
	public void retry(long messageId) {
		if (!outbox.retry(messageId)) {
			throw ApiException.conflict("NOT_FAILED", "Only a failed message can be sent again");
		}
		audit.record("MESSAGE_RETRIED", "message_outbox", messageId, null, Map.of("id", messageId));
	}
}
