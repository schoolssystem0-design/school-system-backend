package com.tirvextech.sms.shared.messaging.smsgate;

import com.tirvextech.sms.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Each school's SMS Gate connection (its own phone and SIM), with secrets decrypted only when needed. */
@Component
public class SmsGateways {

	public static final String WEBHOOK_PATH = "/api/v1/public/sms-gate/webhook/";

	/** A phone that has not been heard from for this long counts as offline. */
	public static final long OFFLINE_AFTER_MINUTES = 30;

	public record Gateway(long schoolId, boolean enabled, SmsGateClient.Connection connection, String signingKey,
			String webhookToken, Integer simNumber, String alertEmail, Instant webhooksRegisteredAt, Instant lastSeenAt,
			Integer batteryLevel, Boolean charging, Instant offlineAlertedAt, String connectedBy, Instant connectedAt,
			Instant lastRefusedAt, String lastRefusedReason) {

		public boolean usable() {
			return enabled && connection.password() != null;
		}
	}

	private final JdbcTemplate jdbc;

	private final SecretBox secrets;

	private final String publicApiUrl;

	private final SecureRandom random = new SecureRandom();

	SmsGateways(JdbcTemplate jdbc, SecretBox secrets, @Value("${app.public-api-url:http://localhost:8080}") String publicApiUrl) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.publicApiUrl = publicApiUrl.endsWith("/") ? publicApiUrl.substring(0, publicApiUrl.length() - 1) : publicApiUrl;
	}

	private static final String COLUMNS = """
			select school_id, enabled, base_url, username, password_enc, signing_key_enc, webhook_token, sim_number,
			       alert_email, webhooks_registered_at, last_seen_at, battery_level, charging, offline_alerted_at,
			       connected_by, connected_at, last_refused_at, last_refused_reason
			from sms_gateway""";

	private Gateway map(ResultSet rs) throws SQLException {
		return new Gateway(rs.getLong(1), rs.getBoolean(2),
				new SmsGateClient.Connection(rs.getString(3), rs.getString(4), secrets.decrypt(rs.getString(5))),
				secrets.decrypt(rs.getString(6)), rs.getString(7), (Integer) rs.getObject(8), rs.getString(9),
				instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), (Integer) rs.getObject(12),
				(Boolean) rs.getObject(13), instant(rs.getTimestamp(14)), rs.getString(15), instant(rs.getTimestamp(16)),
				instant(rs.getTimestamp(17)), rs.getString(18));
	}

	/** The connection of a school (row-level security: callers run as that school or across all schools). */
	public Optional<Gateway> forSchool(long schoolId) {
		return jdbc.query(COLUMNS + " where school_id = ?", (rs, i) -> map(rs), schoolId).stream().findFirst();
	}

	public Optional<Gateway> current() {
		return forSchool(TenantContext.requireSchoolId());
	}

	/** Finds the school a webhook belongs to. Runs across all schools: the caller is the phone, not a signed-in user. */
	public Optional<Gateway> byWebhookToken(String token) {
		if (token == null || token.length() < 20) {
			return Optional.empty();
		}
		return TenantContext.callAcrossAllSchools(() -> jdbc
			.query(COLUMNS + " where webhook_token = ?", (rs, i) -> map(rs), token)
			.stream()
			.findFirst());
	}

	/** Local fake phone only: the signing key of the school connected with this username. */
	String signingKeyForUsername(String username) {
		return TenantContext.callAcrossAllSchools(() -> jdbc
			.query(COLUMNS + " where username = ? order by school_id", (rs, i) -> map(rs), username)
			.stream()
			.findFirst()
			.map(Gateway::signingKey)
			.orElse(null));
	}

	/** Gateways switched on, across all schools (for background jobs). */
	public List<Gateway> allEnabled() {
		return TenantContext.callAcrossAllSchools(() -> jdbc.query(COLUMNS + " where enabled", (rs, i) -> map(rs)));
	}

	/**
	 * Saves the current school's connection. A blank password or signing key keeps the stored one.
	 *
	 * @return the saved gateway
	 */
	public Gateway save(String baseUrl, String username, String password, String signingKey, Integer simNumber,
			String alertEmail, boolean enabled, String actor) {
		long schoolId = TenantContext.requireSchoolId();
		Optional<Gateway> before = forSchool(schoolId);
		String passwordEnc = password == null || password.isBlank() ? null : secrets.encrypt(password.trim());
		String keyEnc = signingKey == null || signingKey.isBlank() ? null : secrets.encrypt(signingKey.trim());
		if (before.isEmpty()) {
			jdbc.update("""
					insert into sms_gateway (school_id, enabled, base_url, username, password_enc, signing_key_enc,
					                         webhook_token, sim_number, alert_email, connected_by)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, enabled, baseUrl, username, passwordEnc, keyEnc,
					newToken(), simNumber, alertEmail, actor);
		}
		else {
			boolean moved = !before.get().connection().baseUrl().equals(baseUrl)
					|| !before.get().connection().username().equals(username);
			jdbc.update("""
					update sms_gateway set enabled = ?, base_url = ?, username = ?,
					       password_enc = coalesce(?, password_enc), signing_key_enc = coalesce(?, signing_key_enc),
					       sim_number = ?, alert_email = ?, connected_by = ?, updated_at = now(),
					       webhooks_registered_at = case when ? then null else webhooks_registered_at end
					where school_id = ?""", enabled, baseUrl, username, passwordEnc, keyEnc, simNumber, alertEmail,
					actor, moved, schoolId);
		}
		return forSchool(schoolId).orElseThrow();
	}

	public void delete() {
		jdbc.update("delete from sms_gateway where school_id = ?", TenantContext.requireSchoolId());
	}

	public void markWebhooksRegistered(long schoolId) {
		jdbc.update("update sms_gateway set webhooks_registered_at = now() where school_id = ?", schoolId);
	}

	/** The phone was heard from. Health values are kept when the event carries none. */
	public void seen(long schoolId, Integer batteryLevel, Boolean charging, String healthJson) {
		jdbc.update("""
				update sms_gateway set last_seen_at = now(), offline_alerted_at = null, last_refused_at = null,
				       last_refused_reason = null,
				       battery_level = coalesce(?, battery_level), charging = coalesce(?, charging),
				       health = coalesce(cast(? as jsonb), health)
				where school_id = ?""", batteryLevel, charging, healthJson, schoolId);
	}

	/** A report from the phone was refused (the caller is the phone, so this runs across all schools). */
	public void refused(long schoolId, String reason) {
		TenantContext.callAcrossAllSchools(() -> jdbc.update(
				"update sms_gateway set last_refused_at = now(), last_refused_reason = ? where school_id = ?",
				reason.length() > 300 ? reason.substring(0, 300) : reason, schoolId));
	}

	public void markOfflineAlerted(long schoolId) {
		jdbc.update("update sms_gateway set offline_alerted_at = now() where school_id = ?", schoolId);
	}

	public String webhookUrlPrefix() {
		return publicApiUrl + WEBHOOK_PATH;
	}

	public String webhookUrl(Gateway g) {
		return webhookUrlPrefix() + g.webhookToken();
	}

	public boolean online(Gateway g, Instant now) {
		return g.lastSeenAt() != null && g.lastSeenAt().isAfter(now.minusSeconds(OFFLINE_AFTER_MINUTES * 60));
	}

	private String newToken() {
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	static Timestamp timestamp(Instant i) {
		return i == null ? null : Timestamp.from(i);
	}
}
