package com.tirvextech.sms.shared.messaging.smsgate;

import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.messaging.MessageOutbox;
import com.tirvextech.sms.shared.security.PublicEndpoint;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives events from a school's phone (SMS Gate webhooks): delivery reports, replies (STOP / START) and health pings.
 * The phone signs every request: HMAC-SHA256 over the raw body followed by the X-Timestamp value, with the signing key
 * from the app (Settings → Webhooks → Signing Key). Unsigned, wrongly signed or old requests are refused.
 */
@RestController
class SmsGateWebhookController {

	private static final Logger log = LoggerFactory.getLogger(SmsGateWebhookController.class);

	/** Requests older (or newer) than this are refused, so a captured request cannot be replayed later. */
	private static final long MAX_SKEW_SECONDS = 300;

	private static final Set<String> STOP_WORDS = Set.of("STOP", "UNSUBSCRIBE", "BAND", "BAND KARO", "BUND");

	private static final Set<String> START_WORDS = Set.of("START", "SUBSCRIBE", "SHURU");

	private final SmsGateways gateways;

	private final MessageOutbox outbox;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final AuditService audit;

	SmsGateWebhookController(SmsGateways gateways, MessageOutbox outbox, JdbcTemplate jdbc, JsonMapper json,
			PlatformTransactionManager transactionManager, Clock clock, AuditService audit) {
		this.audit = audit;
		this.gateways = gateways;
		this.outbox = outbox;
		this.jdbc = jdbc;
		this.json = json;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** Called by the phone. Always answers 2xx once the request is genuine, so the phone does not repeat it forever. */
	@PublicEndpoint
	@PostMapping(SmsGateways.WEBHOOK_PATH + "{token}")
	ResponseEntity<Void> receive(@PathVariable String token,
			@RequestHeader(value = "X-Signature", required = false) String signature,
			@RequestHeader(value = "X-Timestamp", required = false) String timestamp, @RequestBody(required = false) byte[] body) {
		var gateway = gateways.byWebhookToken(token);
		if (gateway.isEmpty()) {
			log.warn("SMS phone report refused: unknown address (the phone may still use an old connection)");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String refused = body == null ? "The phone sent an empty report"
				: refusal(gateway.get().signingKey(), body, signature, timestamp);
		if (refused != null) {
			// Recorded so the SMS phone page says what to fix, instead of the phone just looking offline.
			gateways.refused(gateway.get().schoolId(), refused);
			log.warn("SMS phone report refused for school {}: {}", gateway.get().schoolId(), refused);
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		long schoolId = gateway.get().schoolId();
		JsonNode event;
		try {
			event = json.readTree(body);
		}
		catch (RuntimeException e) {
			return ResponseEntity.badRequest().build();
		}
		TenantContext.runAsSchool(schoolId, () -> transaction.executeWithoutResult(tx -> handle(schoolId, event)));
		return ResponseEntity.ok().build();
	}

	private void handle(long schoolId, JsonNode envelope) {
		String name = SmsGateClient.text(envelope.path("event"));
		String eventId = SmsGateClient.text(envelope.path("id"));
		if (name == null) {
			return;
		}
		if (eventId != null && jdbc.update("""
				insert into sms_gateway_event (school_id, event_id, event) values (?, ?, ?)
				on conflict do nothing""", schoolId, eventId, name) == 0) {
			return; // a repeat of an event already handled
		}
		JsonNode payload = envelope.path("payload");
		Integer battery = null;
		Boolean charging = null;
		String health = null;
		if ("system:ping".equals(name) && payload.has("health")) {
			JsonNode checks = payload.path("health").path("checks");
			battery = intValue(checks.path("battery:level").path("observedValue"));
			Integer plugged = intValue(checks.path("battery:charging").path("observedValue"));
			charging = plugged == null ? null : plugged > 0;
			health = json.writeValueAsString(payload.path("health"));
		}
		gateways.seen(schoolId, battery, charging, health);
		switch (name) {
			case "sms:sent", "sms:delivered", "sms:failed", "sms:cancelled" -> {
				String messageId = SmsGateClient.text(payload.path("messageId"));
				if (messageId != null) {
					Instant at = time(payload, switch (name) {
						case "sms:sent" -> "sentAt";
						case "sms:delivered" -> "deliveredAt";
						case "sms:failed" -> "failedAt";
						default -> "cancelledAt";
					});
					outbox.applyReport(messageId, name, SmsGateClient.text(payload.path("reason")), at);
				}
			}
			case "sms:received" -> reply(schoolId, SmsGateClient.text(payload.path("sender")),
					SmsGateClient.text(payload.path("message")));
			default -> {
			}
		}
	}

	/** STOP turns SMS off for the family that replied; START turns it back on. Both are confirmed by SMS. */
	private void reply(long schoolId, String from, String text) {
		if (from == null || text == null) {
			return;
		}
		String word = text.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", "").replaceAll("\\s+", " ").trim();
		boolean stop = STOP_WORDS.contains(word);
		boolean start = START_WORDS.contains(word);
		if (!stop && !start) {
			return;
		}
		String local = localNumber(from);
		if (local == null) {
			return;
		}
		int families = jdbc.update("""
				update family set preferred_channel = ?, updated_at = now(), version = version + 1
				where (father_phone = ? or mother_phone = ?) and preferred_channel <> ?""", stop ? "NONE" : "SMS", local,
				local, stop ? "NONE" : "SMS");
		if (families == 0) {
			return;
		}
		audit.record(stop ? "SMS_OPTED_OUT" : "SMS_OPTED_IN", "family", null, null,
				Map.of("phone", local, "families", families), "Parent replied " + word + " by SMS");
		String school = jdbc.queryForObject("select name from school where id = ?", String.class, schoolId);
		String confirm = stop ? school + ": You will no longer receive SMS from us. Reply START to receive them again."
				: school + ": SMS from the school are on again. Reply STOP to turn them off.";
		outbox.queueSms(local, confirm, "OPT_OUT_CONFIRM", "family", null, null);
		log.info("Family SMS {} by reply for school {}", stop ? "turned off" : "turned on", schoolId);
	}

	/** "+923001234567" → "03001234567" (how family numbers are stored). */
	static String localNumber(String phone) {
		String digits = phone.replaceAll("\\D", "");
		if (digits.startsWith("92") && digits.length() == 12) {
			return "0" + digits.substring(2);
		}
		return digits.startsWith("0") ? digits : null;
	}

	/** @return null if the request is genuine, otherwise why it is refused (shown to the school's admin) */
	String refusal(String key, byte[] body, String signature, String timestamp) {
		if (key == null) {
			return "The signing key cannot be read on the server: save the connection again with the signing key";
		}
		if (signature == null || timestamp == null) {
			return "The phone sent no signature: update the SMS Gate app";
		}
		long ts;
		try {
			ts = Long.parseLong(timestamp.trim());
		}
		catch (NumberFormatException e) {
			return "The phone sent an unreadable time stamp";
		}
		long skew = clock.instant().getEpochSecond() - ts;
		if (Math.abs(skew) > MAX_SKEW_SECONDS) {
			return "The phone's clock is " + Math.abs(skew / 60) + " minutes " + (skew > 0 ? "behind" : "ahead")
					+ ": turn on automatic date and time on the phone";
		}
		byte[] expected = sign(key, body, timestamp.trim());
		byte[] given;
		try {
			given = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			return "The phone sent an unreadable signature";
		}
		return MessageDigest.isEqual(expected, given) ? null
				: "The signature does not match: copy the signing key again from the app (Settings, Webhooks, Signing Key) and save";
	}

	/** HMAC-SHA256 of the body followed by the timestamp, as the SMS Gate app signs webhooks. */
	public static byte[] sign(String key, byte[] body, String timestamp) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update(body);
			mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
			return mac.doFinal();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Integer intValue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isBoolean()) {
			return node.asBoolean() ? 1 : 0;
		}
		if (node.isNumber()) {
			return node.asInt();
		}
		try {
			return (int) Double.parseDouble(node.asString());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static Instant time(JsonNode payload, String field) {
		String value = SmsGateClient.text(payload.path(field));
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (RuntimeException e) {
			return null;
		}
	}
}
