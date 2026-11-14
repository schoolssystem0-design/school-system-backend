package com.selectcode.sms.iam.app;

import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.iam.domain.PasswordPolicy;
import com.selectcode.sms.iam.infra.AppUserRepository;
import com.selectcode.sms.iam.infra.PasswordTokens;
import com.selectcode.sms.iam.infra.SessionRevoker;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageSender;
import com.selectcode.sms.shared.messaging.OutgoingMessage;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.SchoolLookup;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The user's own account: change password, complete an account setup link, request and confirm a password reset
 * (FR 2.2, 2.3). Passwords are never shown or retrievable; resets go through one-time links.
 */
@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	public static final Duration RESET_LINK_LIFETIME = Duration.ofMinutes(30);

	public static final Duration SETUP_LINK_LIFETIME = Duration.ofHours(48);

	private final AppUserRepository users;

	private final PasswordTokens tokens;

	private final PasswordEncoder passwordEncoder;

	private final SessionRevoker sessions;

	private final SchoolLookup schools;

	private final MessageSender messages;

	private final AuditService audit;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final String webBaseUrl;

	AccountService(AppUserRepository users, PasswordTokens tokens, PasswordEncoder passwordEncoder,
			SessionRevoker sessions, SchoolLookup schools, MessageSender messages, AuditService audit,
			RateLimiter rateLimiter, PlatformTransactionManager transactionManager, Clock clock,
			@Value("${app.web-base-url:http://localhost:3000}") String webBaseUrl) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.schools = schools;
		this.messages = messages;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
	}

	public String setupLink(String token) {
		return webBaseUrl + "/setup-account?token=" + token;
	}

	public String resetLink(String token) {
		return webBaseUrl + "/reset-password?token=" + token;
	}

	/** Signed-in user changes their own password; other sessions of the user are ended. */
	public void changePassword(String currentPassword, String newPassword, String keepSessionId) {
		ActorPrincipal actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			throw ApiException.businessRule("NOT_A_SCHOOL_USER", "Use the platform account page to change this password");
		}
		long schoolId = TenantContext.requireSchoolId();
		transaction.executeWithoutResult(tx -> {
			AppUser user = users.findById(actor.actorId()).orElseThrow(() -> ApiException.notFound("User"));
			if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
				throw ApiException.businessRule("CURRENT_PASSWORD_WRONG", "The current password is not correct");
			}
			if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
				throw ApiException.businessRule("PASSWORD_UNCHANGED", "Choose a password different from the current one");
			}
			PasswordPolicy.check(newPassword, user.getUsername());
			user.setPassword(passwordEncoder.encode(newPassword), false, clock.instant());
			audit.record("PASSWORD_CHANGED", "app_user", user.getId(), null, null);
		});
		sessions.revoke(schoolId, actor.actorId(), keepSessionId);
	}

	/** Completes an account setup link (new user / new school administrator) or a password reset link. */
	public void completeWithToken(String token, String newPassword) {
		long schoolId = PasswordTokens.schoolOf(token).orElseThrow(AccountService::invalidLink);
		Instant now = clock.instant();
		long userId = TenantContext.callAsSchool(schoolId, () -> transaction.execute(tx -> {
			PasswordTokens.Valid valid = tokens.findValid(token, now).orElseThrow(AccountService::invalidLink);
			AppUser user = users.findById(valid.userId()).orElseThrow(AccountService::invalidLink);
			if (user.getStatus() == AppUser.Status.INACTIVE) {
				throw invalidLink();
			}
			PasswordPolicy.check(newPassword, user.getUsername());
			user.setPassword(passwordEncoder.encode(newPassword), false, now);
			tokens.markUsed(valid.tokenId(), now);
			audit.record(valid.purpose() == PasswordTokens.Purpose.SETUP ? "ACCOUNT_SETUP_COMPLETED"
					: "PASSWORD_RESET_COMPLETED", "app_user", user.getId(), null, null);
			return user.getId();
		}));
		TenantContext.runAsSchool(schoolId, () -> sessions.revoke(schoolId, userId, null));
	}

	/**
	 * Sends a reset link to the user's email if the school, user and email exist. Always behaves the same so the
	 * response never reveals which accounts exist.
	 */
	public void requestReset(String schoolCode, String username, String ip) {
		rateLimiter.check("reset:" + ip, 5, Duration.ofMinutes(15));
		var school = schools.findByCode(schoolCode).filter(SchoolLookup.SchoolRef::active).orElse(null);
		if (school == null || username == null || username.isBlank()) {
			return;
		}
		Instant now = clock.instant();
		TenantContext.runAsSchool(school.id(), () -> transaction.executeWithoutResult(tx -> {
			AppUser user = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
			if (user == null || user.getStatus() != AppUser.Status.ACTIVE || user.getEmail() == null) {
				return;
			}
			PasswordTokens.IssuedToken issued = tokens.issue(user.getId(), PasswordTokens.Purpose.RESET, "EMAIL",
					now.plus(RESET_LINK_LIFETIME), "SYSTEM", null, now);
			boolean sent = messages.send(OutgoingMessage.email(user.getEmail(), "Reset your password",
					"""
							Someone asked to reset the password for %s at %s.

							Open this link within 30 minutes to choose a new password:
							%s

							If this wasn't you, ignore this email; your password stays the same.
							""".formatted(user.getUsername(), school.name(), resetLink(issued.token()))));
			if (!sent) {
				log.warn("Password reset requested but email could not be sent (school {})", school.id());
			}
			audit.record("PASSWORD_RESET_REQUESTED", "app_user", user.getId(), null, Map.of("channel", "EMAIL"));
		}));
	}

	private static ApiException invalidLink() {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LINK_INVALID",
				"This link is invalid or has expired. Ask for a new one.");
	}
}
