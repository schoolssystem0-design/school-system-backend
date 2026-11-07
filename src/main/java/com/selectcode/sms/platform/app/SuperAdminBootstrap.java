package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.domain.PlatformUser;
import com.selectcode.sms.platform.infra.PlatformUserRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates the first super admin from {@code BOOTSTRAP_SUPERADMIN_EMAIL} / {@code _PASSWORD} when none exists. The
 * authenticator app is enrolled at first sign-in. Remove the variables after the first sign-in (runbook §9).
 *
 * <p>Changing bootstrap env vars later does <strong>not</strong> change an existing account. On staging only,
 * {@code BOOTSTRAP_SUPERADMIN_RESET_PASSWORD=true} reapplies the password for the configured email (one-time recovery).
 */
@Component
class SuperAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

	private final PlatformUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	private final boolean stagingProfile;

	private final String email;

	private final String password;

	private final String name;

	private final boolean resetPassword;

	SuperAdminBootstrap(PlatformUserRepository users, PasswordEncoder passwordEncoder, Clock clock,
			Environment environment, @Value("${app.bootstrap.superadmin-email:}") String email,
			@Value("${app.bootstrap.superadmin-password:}") String password,
			@Value("${app.bootstrap.superadmin-name:System Owner}") String name,
			@Value("${app.bootstrap.superadmin-reset-password:false}") boolean resetPassword) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.stagingProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch("staging"::equals);
		this.email = email;
		this.password = password;
		this.name = name;
		this.resetPassword = resetPassword;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!StringUtils.hasText(email)) {
			return;
		}
		String trimmedEmail = email.trim();
		boolean validPassword = password != null && password.length() >= 12;
		Optional<PlatformUser> existing = users.findByEmailIgnoreCase(trimmedEmail);

		if (existing.isPresent()) {
			if (resetPassword && stagingProfile && validPassword) {
				PlatformUser user = existing.get();
				// One-time recovery: only when the stored password differs. Once applied, later restarts (every
				// deploy) leave the account alone, so the authenticator set up afterwards is not wiped each time.
				if (passwordEncoder.matches(password, user.getPasswordHash())) {
					log.warn("BOOTSTRAP_SUPERADMIN_RESET_PASSWORD is still set but already applied for {}; remove it",
							trimmedEmail);
					return;
				}
				user.applyBootstrapPassword(passwordEncoder.encode(password), clock.instant());
				users.save(user);
				log.warn(
						"Applied bootstrap password for super admin {} (staging reset); remove BOOTSTRAP_SUPERADMIN_RESET_PASSWORD after sign-in",
						trimmedEmail);
			}
			return;
		}

		if (users.count() > 0) {
			log.warn(
					"Super admin bootstrap skipped: {} platform user(s) exist with a different email than BOOTSTRAP_SUPERADMIN_EMAIL",
					users.count());
			return;
		}

		if (!validPassword) {
			throw new IllegalStateException("BOOTSTRAP_SUPERADMIN_PASSWORD must be at least 12 characters");
		}
		users.save(new PlatformUser(trimmedEmail, name, passwordEncoder.encode(password)));
		log.info("Created the first super admin account; enrol the authenticator app at first sign-in");
	}
}
