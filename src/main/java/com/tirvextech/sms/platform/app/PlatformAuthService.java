package com.tirvextech.sms.platform.app;

import com.tirvextech.sms.platform.PlatformPrincipal;
import com.tirvextech.sms.platform.domain.PlatformUser;
import com.tirvextech.sms.platform.infra.PlatformUserRepository;
import com.tirvextech.sms.shared.messaging.MessageSender;
import com.tirvextech.sms.shared.messaging.OutgoingMessage;
import com.tirvextech.sms.shared.security.Totp;
import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.web.RateLimiter;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Super admin sign-in: email + password, then a one-time code emailed to the user (default). Authenticator (TOTP) remains
 * available when {@code app.platform.second-factor=totp}.
 */
@Service
public class PlatformAuthService {

	public static final String TOTP_ISSUER = "School Management Platform";

	static final String PENDING_ENROLMENT = "sms.platform.pendingEnrolment";

	static final String PENDING_EMAIL = "sms.platform.pendingEmail";

	private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(10);

	private static final SecureRandom RANDOM = new SecureRandom();

	public sealed interface Outcome {

		record SignedIn(PlatformPrincipal principal) implements Outcome {
		}

		/** Password was right but no authenticator is enrolled yet (TOTP mode only). */
		record EnrolmentRequired(String secret, String otpauthUri) implements Outcome {
		}
	}

	public enum SecondFactor {
		OFF, EMAIL, TOTP
	}

	record PendingEnrolment(long platformUserId, String secret, Instant expiresAt) implements Serializable {
	}

	record PendingEmailLogin(long platformUserId, String codeSha256, Instant expiresAt) implements Serializable {
	}

	private final PlatformUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final RateLimiter rateLimiter;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final MessageSender messages;

	private final String dummyHash;

	private final SecondFactor secondFactor;

	PlatformAuthService(PlatformUserRepository users, PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
			JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock, MessageSender messages,
			@Value("${app.platform.second-factor:email}") String secondFactorConfig) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.rateLimiter = rateLimiter;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.messages = messages;
		this.secondFactor = parseSecondFactor(secondFactorConfig);
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	static SecondFactor parseSecondFactor(String value) {
		if (value == null || value.isBlank()) {
			return SecondFactor.EMAIL;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "off", "none", "false" -> SecondFactor.OFF;
			case "totp", "authenticator" -> SecondFactor.TOTP;
			default -> SecondFactor.EMAIL;
		};
	}

	public Outcome signIn(String email, String password, String totpCode, String emailCode, String ip, String userAgent,
			HttpSession session) {
		rateLimiter.check("platform-signin:" + ip, 10, Duration.ofMinutes(5));
		Object result = transaction
			.execute(tx -> attempt(email, password, totpCode, emailCode, ip, userAgent, session));
		if (result instanceof ApiException error) {
			throw error;
		}
		return (Outcome) result;
	}

	private Object attempt(String email, String password, String totpCode, String emailCode, String ip, String userAgent,
			HttpSession session) {
		Instant now = clock.instant();
		PlatformUser user = users.findByEmailIgnoreCase(email.trim()).orElse(null);
		if (user == null || !user.isActive()) {
			passwordEncoder.matches(password, dummyHash);
			record(user, email, "INVALID", ip, userAgent);
			return invalid();
		}
		if (user.isLocked(now)) {
			record(user, email, "BLOCKED", ip, userAgent);
			return new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Too many failed attempts. Try again later.");
		}
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			user.recordFailure(now);
			record(user, email, "INVALID", ip, userAgent);
			return invalid();
		}
		return switch (secondFactor) {
			case OFF -> {
				user.recordSuccess(now);
				record(user, email, "SUCCESS", ip, userAgent);
				yield new Outcome.SignedIn(principal(user));
			}
			case EMAIL -> handleEmailCode(user, emailCode, session, now, ip, userAgent);
			case TOTP -> handleTotp(user, totpCode, session, now, email, ip, userAgent);
		};
	}

	private Object handleEmailCode(PlatformUser user, String emailCode, HttpSession session, Instant now, String ip,
			String userAgent) {
		if (emailCode != null && !emailCode.isBlank()) {
			Object value = session.getAttribute(PENDING_EMAIL);
			if (!(value instanceof PendingEmailLogin pending) || pending.platformUserId() != user.getId()
					|| pending.expiresAt().isBefore(now)) {
				return new ApiException(HttpStatus.UNAUTHORIZED, "EMAIL_CODE_EXPIRED",
						"The code has expired. Sign in with your password again to get a new one.");
			}
			if (!codeMatches(pending.codeSha256(), emailCode.trim())) {
				user.recordFailure(now);
				record(user, user.getEmail(), "EMAIL_CODE_INVALID", ip, userAgent);
				return new ApiException(HttpStatus.UNAUTHORIZED, "EMAIL_CODE_INVALID", "The sign-in code is not correct");
			}
			session.removeAttribute(PENDING_EMAIL);
			user.recordSuccess(now);
			record(user, user.getEmail(), "SUCCESS", ip, userAgent);
			return new Outcome.SignedIn(principal(user));
		}

		Object existing = session.getAttribute(PENDING_EMAIL);
		if (existing instanceof PendingEmailLogin pending && pending.platformUserId() == user.getId()
				&& pending.expiresAt().isAfter(now)) {
			return new ApiException(HttpStatus.UNAUTHORIZED, "EMAIL_CODE_REQUIRED",
					"Enter the sign-in code we sent to your email.");
		}

		String digits = generateDigits(6);
		session.setAttribute(PENDING_EMAIL,
				new PendingEmailLogin(user.getId(), sha256(digits), now.plus(EMAIL_CODE_TTL)));
		boolean sent = messages.send(OutgoingMessage.email(user.getEmail(), "School Management Platform sign-in code", """
				You asked to sign in to the School Management Platform.

				Your sign-in code is: %s

				It works for 10 minutes. If you did not try to sign in, ignore this email.
				""".formatted(digits)));
		if (!sent) {
			session.removeAttribute(PENDING_EMAIL);
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_NOT_SENT",
					"We could not send a sign-in code to your email. Ask your administrator to configure email (SMTP or API).");
		}
		return new ApiException(HttpStatus.UNAUTHORIZED, "EMAIL_CODE_REQUIRED",
				"We sent a sign-in code to your email. Enter it below.");
	}

	private Object handleTotp(PlatformUser user, String totpCode, HttpSession session, Instant now, String email,
			String ip, String userAgent) {
		if (user.getTotpSecret() == null) {
			String secret = Totp.newSecret();
			session.setAttribute(PENDING_ENROLMENT,
					new PendingEnrolment(user.getId(), secret, now.plus(Duration.ofMinutes(10))));
			return new Outcome.EnrolmentRequired(secret, Totp.otpauthUri(TOTP_ISSUER, user.getEmail(), secret));
		}
		if (totpCode == null || totpCode.isBlank()) {
			return new ApiException(HttpStatus.UNAUTHORIZED, "TOTP_REQUIRED", "Enter the code from your authenticator app");
		}
		if (!Totp.verify(user.getTotpSecret(), totpCode, now)) {
			user.recordFailure(now);
			record(user, email, "TOTP_INVALID", ip, userAgent);
			return new ApiException(HttpStatus.UNAUTHORIZED, "TOTP_INVALID", "The authenticator code is not correct");
		}
		user.recordSuccess(now);
		record(user, email, "SUCCESS", ip, userAgent);
		return new Outcome.SignedIn(principal(user));
	}

	/** Completes first sign-in in TOTP mode: the code proves the authenticator app was set up with the new secret. */
	public PlatformPrincipal completeEnrolment(String code, String ip, String userAgent, HttpSession session) {
		rateLimiter.check("platform-enrol:" + ip, 10, Duration.ofMinutes(5));
		Object value = session.getAttribute(PENDING_ENROLMENT);
		Instant now = clock.instant();
		if (!(value instanceof PendingEnrolment pending) || pending.expiresAt().isBefore(now)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ENROLMENT_EXPIRED",
					"The setup has expired. Sign in again.");
		}
		if (!Totp.verify(pending.secret(), code, now)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TOTP_INVALID",
					"The authenticator code is not correct");
		}
		session.removeAttribute(PENDING_ENROLMENT);
		return transaction.execute(tx -> {
			PlatformUser user = users.findById(pending.platformUserId()).orElseThrow(PlatformAuthService::invalid);
			user.enrollTotp(pending.secret(), now);
			user.recordSuccess(now);
			record(user, user.getEmail(), "SUCCESS", ip, userAgent);
			return principal(user);
		});
	}

	private static String generateDigits(int length) {
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(RANDOM.nextInt(10));
		}
		return sb.toString();
	}

	private static String sha256(String plain) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean codeMatches(String expectedSha256, String typed) {
		String normalized = typed.replaceAll("\\D", "");
		if (normalized.length() != 6) {
			return false;
		}
		return MessageDigest.isEqual(expectedSha256.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				sha256(normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static PlatformPrincipal principal(PlatformUser user) {
		return new PlatformPrincipal(user.getId(), user.getEmail(), user.getName(), null, null, null);
	}

	private void record(PlatformUser user, String email, String result, String ip, String userAgent) {
		jdbc.update("""
				insert into platform_login_event (platform_user_id, email, result, ip, user_agent)
				values (?, ?, ?, ?, ?)""", user == null ? null : user.getId(), truncate(email, 254), result,
				truncate(ip, 64), truncate(userAgent, 400));
	}

	private static String truncate(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max);
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
	}
}