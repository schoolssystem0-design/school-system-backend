package com.tirvextech.sms.communication.app;

import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.messaging.MessageOutbox;
import com.tirvextech.sms.shared.messaging.smsgate.SecretBox;
import com.tirvextech.sms.shared.messaging.smsgate.SmsGateClient;
import com.tirvextech.sms.shared.messaging.smsgate.SmsGateways;
import com.tirvextech.sms.shared.messaging.smsgate.SmsGateways.Gateway;
import com.tirvextech.sms.shared.security.CurrentActor;
import com.tirvextech.sms.shared.validation.PakFormats;
import com.tirvextech.sms.shared.web.ApiException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The school's own SMS phone (SMS Gate app on an Android phone with the school's SIM): connect it, check it, send a
 * test. Credentials are verified with SMS Gate before they are stored, stored encrypted, and never shown again.
 */
@Service
public class SmsPhoneService {

	public record Connect(String baseUrl, String username, String password, String signingKey, Integer simNumber,
			String alertEmail, Boolean enabled) {
	}

	public record FailedLine(long id, String recipient, String purpose, String error, Instant createdAt) {
	}

	public record PhoneStatus(boolean configured, boolean enabled, String baseUrl, String defaultBaseUrl, String username,
			Integer simNumber, String alertEmail, String webhookUrl, boolean webhooksRegistered, boolean online,
			Instant lastSeenAt, Integer batteryLevel, Boolean charging, Map<String, Integer> today, int waiting,
			int dailyLimit, int handedOverToday, List<FailedLine> failed, boolean alert, boolean secretsAvailable,
			String connectedBy, Instant connectedAt, Instant lastRefusedAt, String lastRefusedReason, String notice) {

		PhoneStatus withNotice(String text) {
			return new PhoneStatus(configured, enabled, baseUrl, defaultBaseUrl, username, simNumber, alertEmail, webhookUrl,
					webhooksRegistered, online, lastSeenAt, batteryLevel, charging, today, waiting, dailyLimit, handedOverToday,
					failed, alert, secretsAvailable, connectedBy, connectedAt, lastRefusedAt, lastRefusedReason, text);
		}
	}

	public record SmsTestResult(long messageId) {
	}

	private static final List<String> STATUSES = List.of("PENDING", "QUEUED", "SENT", "DELIVERED", "FAILED", "CANCELLED");

	private final SmsGateways gateways;

	private final SmsGateClient client;

	private final SecretBox secrets;

	private final MessageOutbox outbox;

	private final SettingsService settings;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final String defaultBaseUrl;

	SmsPhoneService(SmsGateways gateways, SmsGateClient client, SecretBox secrets, MessageOutbox outbox,
			SettingsService settings, AuditService audit, JdbcTemplate jdbc, Clock clock,
			@Value("${app.sms-gate.default-url:https://api.sms-gate.app/3rdparty/v1}") String defaultBaseUrl) {
		this.gateways = gateways;
		this.client = client;
		this.secrets = secrets;
		this.outbox = outbox;
		this.settings = settings;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
		this.defaultBaseUrl = defaultBaseUrl;
	}

	@Transactional(readOnly = true)
	public PhoneStatus status() {
		Optional<Gateway> g = gateways.current();
		Instant now = clock.instant();
		Timestamp today = Timestamp.from(outbox.startOfToday());
		Map<String, Integer> counts = new LinkedHashMap<>();
		STATUSES.forEach(s -> counts.put(s, 0));
		jdbc.query("select status, count(*) from message_outbox where channel = 'SMS' and created_at >= ? group by status",
				rs -> {
					counts.put(rs.getString(1), rs.getInt(2));
				}, today);
		Integer handed = jdbc.queryForObject("select count(*) from message_outbox where queued_at >= ?", Integer.class, today);
		List<FailedLine> failed = jdbc.query("""
				select id, recipient, purpose, last_error, created_at from message_outbox
				where channel = 'SMS' and status = 'FAILED' order by id desc limit 5""",
				(rs, i) -> new FailedLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getTimestamp(5).toInstant()));
		int waiting = outbox.waiting();
		if (g.isEmpty()) {
			return new PhoneStatus(false, false, defaultBaseUrl, defaultBaseUrl, null, null, null, null, false, false, null,
					null, null, counts, waiting, settings.integer(SettingKey.SMS_DAILY_LIMIT), handed == null ? 0 : handed,
					failed, false, secrets.available(), null, null, null, null, null);
		}
		Gateway gw = g.get();
		boolean online = gateways.online(gw, now);
		return new PhoneStatus(true, gw.enabled(), gw.connection().baseUrl(), defaultBaseUrl, gw.connection().username(),
				gw.simNumber(), gw.alertEmail(), gateways.webhookUrl(gw), gw.webhooksRegisteredAt() != null, online,
				gw.lastSeenAt(), gw.batteryLevel(), gw.charging(), counts, waiting,
				settings.integer(SettingKey.SMS_DAILY_LIMIT), handed == null ? 0 : handed, failed,
				gw.enabled() && !online && waiting > 0, secrets.available(), gw.connectedBy(), gw.connectedAt(),
				gw.lastRefusedAt(), gw.lastRefusedReason(), null);
	}

	/** Checks the login with SMS Gate, stores it and registers the webhooks that report back to this school. */
	@Transactional
	public PhoneStatus connect(Connect in) {
		Optional<Gateway> before = gateways.current();
		String baseUrl = checkedUrl(PakFormats.trimToNull(in.baseUrl()) == null ? defaultBaseUrl : in.baseUrl().trim());
		String username = PakFormats.trimToNull(in.username());
		if (username == null || username.length() > 100) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter the username shown in the SMS Gate app");
		}
		String password = PakFormats.trimToNull(in.password());
		String signingKey = PakFormats.trimToNull(in.signingKey());
		if (password == null && (before.isEmpty() || before.get().connection().password() == null)) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter the password shown in the SMS Gate app");
		}
		if (signingKey == null && (before.isEmpty() || before.get().signingKey() == null)) {
			throw ApiException.businessRule("FIELD_REQUIRED",
					"Enter the webhook signing key (SMS Gate app: Settings, Webhooks, Signing Key)");
		}
		if (signingKey != null && signingKey.length() < 8) {
			throw ApiException.businessRule("SIGNING_KEY_SHORT", "The signing key looks too short; copy it again from the app");
		}
		if (in.simNumber() != null && (in.simNumber() < 1 || in.simNumber() > 3)) {
			throw ApiException.businessRule("SIM_INVALID", "Choose SIM 1, 2 or 3, or leave it to the phone");
		}
		String email = PakFormats.trimToNull(in.alertEmail());
		if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw ApiException.businessRule("EMAIL_INVALID", "Enter a valid email for offline alerts");
		}
		String effectivePassword = password != null ? password : before.get().connection().password();
		SmsGateClient.Connection connection = new SmsGateClient.Connection(baseUrl, username, effectivePassword);
		try {
			client.verifyLogin(connection);
		}
		catch (SmsGateClient.GatewayException e) {
			throw ApiException.businessRule("SMS_GATE_LOGIN", e.getMessage());
		}
		boolean enabled = in.enabled() == null || in.enabled();
		Gateway saved = gateways.save(baseUrl, username, password, signingKey, in.simNumber(), email, enabled,
				CurrentActor.require().displayName());
		String notice = tryRegister(saved);
		audit.record(before.isEmpty() ? "SMS_PHONE_CONNECTED" : "SMS_PHONE_UPDATED", "sms_gateway", null,
				before.map(b -> Map.<String, Object>of("baseUrl", b.connection().baseUrl(), "username",
						b.connection().username(), "enabled", b.enabled())).orElse(null),
				Map.of("baseUrl", baseUrl, "username", username, "enabled", enabled, "passwordChanged", password != null,
						"signingKeyChanged", signingKey != null));
		return status().withNotice(notice);
	}

	/** Registers the webhooks again (after the phone was reset or the app reinstalled). */
	@Transactional
	public PhoneStatus registerWebhooks() {
		register(requireGateway());
		audit.record("SMS_PHONE_WEBHOOKS", "sms_gateway", null, null, Map.of("registered", true));
		return status();
	}

	/**
	 * Registers delivery reports while connecting. A failure does not stop the connection: messages are still sent and
	 * their status is fetched from SMS Gate instead.
	 *
	 * @return null when registered, otherwise what the admin should know
	 */
	private String tryRegister(Gateway g) {
		String url = gateways.webhookUrl(g);
		boolean localServer = g.connection().baseUrl().startsWith("http://localhost")
				|| g.connection().baseUrl().startsWith("http://127.0.0.1");
		if (!url.startsWith("https://") && !localServer) {
			return "Connected. SMS will be sent, but the phone cannot send delivery reports to " + url
					+ " (SMS Gate needs a public https address: set APP_PUBLIC_API_URL on the server). Until then the status of"
					+ " each SMS is checked with SMS Gate about a minute after sending.";
		}
		try {
			register(g);
			return null;
		}
		catch (ApiException e) {
			return "Connected. SMS will be sent, but delivery reports could not be set up (" + e.getMessage()
					+ "). The status of each SMS is checked with SMS Gate instead.";
		}
	}

	private void register(Gateway g) {
		try {
			client.registerWebhooks(g.connection(), gateways.webhookUrl(g), SmsGateways.WEBHOOK_PATH);
			gateways.markWebhooksRegistered(g.schoolId());
		}
		catch (SmsGateClient.GatewayException e) {
			throw ApiException.businessRule("SMS_GATE_WEBHOOKS", "Could not register delivery reports: " + e.getMessage());
		}
	}

	@Transactional
	public void disconnect() {
		requireGateway();
		gateways.delete();
		audit.record("SMS_PHONE_DISCONNECTED", "sms_gateway", null, null, Map.of("disconnected", true));
	}

	/** Sends a test SMS straight away (quiet hours and the daily limit do not apply). */
	@Transactional
	public SmsTestResult test(String rawPhone) {
		Gateway g = requireGateway();
		if (!g.enabled()) {
			throw ApiException.businessRule("SMS_PHONE_OFF", "Switch the SMS phone on first");
		}
		String phone = PakFormats.phone(rawPhone, "Mobile number");
		if (phone == null || MessageOutbox.international(phone) == null) {
			throw ApiException.businessRule("PHONE_INVALID", "Enter a mobile number like 0300-1234567");
		}
		String school = jdbc.queryForObject("select name from school where id = ?", String.class, g.schoolId());
		outbox.queueSms(phone, school + ": Test message from the school system. If you received this, the SMS phone works.",
				"TEST", "sms_gateway", null, null);
		Long id = jdbc.queryForObject("select max(id) from message_outbox where purpose = 'TEST' and recipient = ?",
				Long.class, phone);
		audit.record("SMS_PHONE_TEST", "sms_gateway", null, null, Map.of("phone", phone));
		return new SmsTestResult(id == null ? 0 : id);
	}

	private Gateway requireGateway() {
		return gateways.current().orElseThrow(() -> ApiException.businessRule("SMS_PHONE_NOT_CONNECTED",
				"Connect the school's SMS phone first"));
	}

	/** HTTPS, except a local address used in development. */
	private static String checkedUrl(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.businessRule("URL_INVALID", "Enter the SMS Gate server address, e.g. https://api.sms-gate.app/3rdparty/v1");
		}
		String host = uri.getHost();
		boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
		if (host == null || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
			throw ApiException.businessRule("URL_INVALID", "The SMS Gate server address must start with https://");
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
