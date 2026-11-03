package com.selectcode.sms.iam.infra;

import com.selectcode.sms.shared.tenant.TenantContext;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * One-time tokens for account setup links and password resets (FR 2.3). The token sent to the user is
 * {@code <schoolId>.<random>}; the school prefix lets the server set the tenant before looking the token up. Only the
 * SHA-256 hash is stored.
 */
@Repository
public class PasswordTokens {

	public enum Purpose {
		SETUP, RESET
	}

	public record IssuedToken(String token, Instant expiresAt) {
	}

	public record Valid(long tokenId, long userId, Purpose purpose) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	PasswordTokens(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Issues a new token and invalidates the user's earlier unused tokens of the same purpose. */
	public IssuedToken issue(long userId, Purpose purpose, String channel, Instant expiresAt, String createdByType,
			Long createdById, Instant now) {
		long schoolId = TenantContext.requireSchoolId();
		jdbc.update("update password_token set used_at = ? where user_id = ? and purpose = ? and used_at is null",
				Timestamp.from(now), userId, purpose.name());
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String token = schoolId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		jdbc.update("""
				insert into password_token (school_id, user_id, purpose, token_hash, channel, expires_at,
				                            created_by_type, created_by_id)
				values (?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, userId, purpose.name(), hash(token), channel,
				Timestamp.from(expiresAt), createdByType, createdById);
		return new IssuedToken(token, expiresAt);
	}

	/** The school encoded in a token, if it is well formed. */
	public static OptionalLong schoolOf(String token) {
		if (token == null) {
			return OptionalLong.empty();
		}
		int dot = token.indexOf('.');
		if (dot <= 0 || dot > 19) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Long.parseLong(token.substring(0, dot)));
		}
		catch (NumberFormatException ex) {
			return OptionalLong.empty();
		}
	}

	/** Must run with the token's school in TenantContext. */
	public Optional<Valid> findValid(String token, Instant now) {
		List<Valid> rows = jdbc.query("""
				select id, user_id, purpose from password_token
				where token_hash = ? and used_at is null and expires_at > ?""",
				(rs, i) -> new Valid(rs.getLong(1), rs.getLong(2), Purpose.valueOf(rs.getString(3))), hash(token),
				Timestamp.from(now));
		return rows.stream().findFirst();
	}

	public void markUsed(long tokenId, Instant now) {
		jdbc.update("update password_token set used_at = ? where id = ?", Timestamp.from(now), tokenId);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
