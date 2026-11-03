package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.PlatformPrincipal;
import com.selectcode.sms.platform.domain.PlatformUser;
import com.selectcode.sms.platform.infra.PlatformUserRepository;
import com.selectcode.sms.shared.security.Totp;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.RateLimiter;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Super admin sign-in: email + password + TOTP code (mandatory, DECISIONS D-10). On the first sign-in the super admin
 * enrols an authenticator app: the password step returns a new secret, and the first valid code completes sign-in.
 */
@Service
public class PlatformAuthService {

	public static final String TOTP_ISSUER = "School Management Platform";

	static final String PENDING_ENROLMENT = "sms.platform.pendingEnrolment";

	public sealed interface Outcome {

		record SignedIn(PlatformPrincipal principal) implements Outcome {
		}

		/** Password was right but no authenticator is enrolled yet. */
		record EnrolmentRequired(String secret, String otpauthUri) implements Outcome {
		}
	}

	record PendingEnrolment(long platformUserId, String secret, Instant expiresAt) implements Serializable {
	}

	private final PlatformUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final RateLimiter rateLimiter;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final String dummyHash;

	PlatformAuthService(PlatformUserRepository users, PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
			JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.rateLimiter = rateLimiter;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	public Outcome signIn(String email, String password, String totpCode, String ip, String userAgent,
			HttpSession session) {
		rateLimiter.check("platform-signin:" + ip, 10, Duration.ofMinutes(5));
		// The transaction always commits (failed-attempt counter + login history); errors are thrown afterwards.
		Object result = transaction.execute(tx -> attempt(email, password, totpCode, ip, userAgent, session));
		if (result instanceof ApiException error) {
			throw error;
		}
		return (Outcome) result;
	}

	private Object attempt(String email, String password, String totpCode, String ip, String userAgent,
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

	/** Completes first sign-in: the code proves the authenticator app was set up with the new secret. */
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
