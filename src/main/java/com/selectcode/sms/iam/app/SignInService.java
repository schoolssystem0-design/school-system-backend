package com.selectcode.sms.iam.app;

import com.selectcode.sms.iam.SchoolUserPrincipal;
import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.iam.domain.LoginEvent;
import com.selectcode.sms.iam.infra.AppUserRepository;
import com.selectcode.sms.iam.infra.LoginEventRepository;
import com.selectcode.sms.shared.tenant.SchoolLookup;
import com.selectcode.sms.shared.tenant.SchoolLookup.SchoolRef;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * School user sign-in: school code + username + password (DECISIONS D-01). Records every attempt in login history,
 * flags sign-ins from a new network (FR 2.7, 3.8) and locks the account after repeated failures.
 */
@Service
public class SignInService {

	public record SignInAttempt(String schoolCode, String username, String password, String ip, String userAgent) {
	}

	private final SchoolLookup schools;

	private final AppUserRepository users;

	private final LoginEventRepository loginEvents;

	private final PasswordEncoder passwordEncoder;

	private final TransactionTemplate transaction;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	private final int signInLimit;

	/** Compared against when the user does not exist, so response time doesn't reveal valid usernames. */
	private final String dummyHash;

	SignInService(SchoolLookup schools, AppUserRepository users, LoginEventRepository loginEvents,
			PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager, RateLimiter rateLimiter,
			Clock clock, @Value("${app.security.signin-limit-per-network:30}") int signInLimit) {
		this.schools = schools;
		this.users = users;
		this.loginEvents = loginEvents;
		this.passwordEncoder = passwordEncoder;
		this.transaction = new TransactionTemplate(transactionManager);
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.signInLimit = signInLimit;
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	/**
	 * @return the principal to store in the session
	 * @throws ApiException {@code INVALID_CREDENTIALS} (401), {@code ACCOUNT_LOCKED} or {@code SCHOOL_SUSPENDED} (423)
	 */
	public SchoolUserPrincipal signIn(SignInAttempt attempt) {
		rateLimiter.check("signin:" + attempt.ip(), signInLimit, Duration.ofMinutes(5));
		SchoolRef school = schools.findByCode(attempt.schoolCode()).orElse(null);
		if (school == null) {
			passwordEncoder.matches(attempt.password(), dummyHash);
			throw invalidCredentials();
		}
		if (!school.active()) {
			throw new ApiException(HttpStatus.LOCKED, "SCHOOL_SUSPENDED", "This school's account is suspended");
		}
		// Tenant is set before the transaction starts, so the connection gets the school's RLS context.
		Outcome outcome = TenantContext.callAsSchool(school.id(), () -> transaction.execute(tx -> attempt(school, attempt)));
		return switch (outcome) {
			case Outcome.Success success -> success.principal();
			case Outcome.Locked locked -> throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
					"Too many failed attempts. Try again later or ask your administrator.");
			case Outcome.Invalid invalid -> throw invalidCredentials();
		};
	}

	private sealed interface Outcome {

		record Success(SchoolUserPrincipal principal) implements Outcome {
		}

		record Locked() implements Outcome {
		}

		record Invalid() implements Outcome {
		}
	}

	private Outcome attempt(SchoolRef school, SignInAttempt attempt) {
		Instant now = clock.instant();
		AppUser user = users.findByUsernameIgnoreCase(attempt.username().trim()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(attempt.password(), dummyHash);
			record(null, attempt, LoginEvent.Result.INVALID, now, false);
			return new Outcome.Invalid();
		}
		if (!user.canSignIn(now)) {
			record(user.getId(), attempt, LoginEvent.Result.BLOCKED, now, false);
			return user.getStatus() == AppUser.Status.INACTIVE ? new Outcome.Invalid() : new Outcome.Locked();
		}
		if (!passwordEncoder.matches(attempt.password(), user.getPasswordHash())) {
			users.recordFailedSignIn(user.getId(), AppUser.MAX_FAILED_ATTEMPTS, now.plus(AppUser.LOCK_DURATION));
			record(user.getId(), attempt, LoginEvent.Result.INVALID, now, false);
			return new Outcome.Invalid();
		}
		users.recordSuccessfulSignIn(user.getId(), now);
		record(user.getId(), attempt, LoginEvent.Result.SUCCESS, now, isNewLocation(user.getId(), attempt.ip()));
		return new Outcome.Success(new SchoolUserPrincipal(user.getId(), school.id(), school.code(), school.name(),
				user.getUsername(), user.getDisplayName()));
	}

	/** New location = the user has signed in before, but never from this network. */
	private boolean isNewLocation(long userId, String ip) {
		String prefix = LoginEvent.prefixOf(ip);
		if (prefix == null) {
			return false;
		}
		return loginEvents.countSuccessful(userId) > 0 && loginEvents.countSuccessfulFromPrefix(userId, prefix) == 0;
	}

	private void record(Long userId, SignInAttempt attempt, LoginEvent.Result result, Instant now,
			boolean newLocation) {
		loginEvents.save(new LoginEvent(userId, attempt.username(), result, attempt.ip(), attempt.userAgent(), now,
				newLocation));
	}

	private static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
				"School code, username or password is incorrect");
	}
}
