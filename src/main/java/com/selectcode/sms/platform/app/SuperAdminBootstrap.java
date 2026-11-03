package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.domain.PlatformUser;
import com.selectcode.sms.platform.infra.PlatformUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates the first super admin from {@code BOOTSTRAP_SUPERADMIN_EMAIL} / {@code _PASSWORD} when none exists. The
 * authenticator app is enrolled at first sign-in. Remove the variables after the first sign-in (runbook §9).
 */
@Component
class SuperAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

	private final PlatformUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final String email;

	private final String password;

	private final String name;

	SuperAdminBootstrap(PlatformUserRepository users, PasswordEncoder passwordEncoder,
			@Value("${app.bootstrap.superadmin-email:}") String email,
			@Value("${app.bootstrap.superadmin-password:}") String password,
			@Value("${app.bootstrap.superadmin-name:System Owner}") String name) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.email = email;
		this.password = password;
		this.name = name;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!StringUtils.hasText(email) || users.count() > 0) {
			return;
		}
		if (password == null || password.length() < 12) {
			throw new IllegalStateException("BOOTSTRAP_SUPERADMIN_PASSWORD must be at least 12 characters");
		}
		users.save(new PlatformUser(email.trim(), name, passwordEncoder.encode(password)));
		log.info("Created the first super admin account; enrol the authenticator app at first sign-in");
	}
}
