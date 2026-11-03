package com.selectcode.sms;

import com.selectcode.sms.platform.app.SchoolManagementService;
import com.selectcode.sms.platform.app.SchoolManagementService.NewSchool;
import com.selectcode.sms.support.TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Runs the API locally on an embedded PostgreSQL (no Docker or installed database needed):
 * {@code mvn spring-boot:test-run -Dspring-boot.run.main-class=com.selectcode.sms.LocalDevApplication}.
 *
 * <p>Seeds a super admin and two demo schools created through the real provisioning code. Data is discarded when the
 * process stops. Emails (setup links, step-up codes) appear at http://localhost:8080/api/v1/dev/outbox.
 */
public class LocalDevApplication {

	static final String DEMO_PASSWORD = "Admin@12345";

	static final String OWNER_EMAIL = "owner@local.test";

	static final String OWNER_PASSWORD = "Owner@123456";

	/** Fixed authenticator secret for local development only: add it to an authenticator app by hand. */
	static final String OWNER_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	public static void main(String[] args) {
		String url = TestDatabase.jdbcUrl();
		System.setProperty("spring.datasource.url", url);
		System.setProperty("spring.datasource.username", TestDatabase.APP_USER);
		System.setProperty("spring.datasource.password", TestDatabase.APP_PASSWORD);
		System.setProperty("spring.flyway.url", url);
		System.setProperty("spring.flyway.user", "postgres");
		System.setProperty("spring.flyway.password", "postgres");
		System.setProperty("app.db.app-user", TestDatabase.APP_USER);
		System.setProperty("app.db.app-password", TestDatabase.APP_PASSWORD);
		// One browser test run signs in more often than the real limit (30 in 5 minutes). This class never runs in production.
		System.setProperty("app.security.signin-limit-per-network", "300");

		ConfigurableApplicationContext context = SpringApplication.from(SmsApplication::main)
			.withAdditionalProfiles("local")
			.run(args)
			.getApplicationContext();
		seed(context.getBean(SchoolManagementService.class));
	}

	private static void seed(SchoolManagementService schools) {
		JdbcTemplate db = TestDatabase.superuser();
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
		db.update("""
				insert into platform_user (email, name, password_hash, totp_secret) values (?, 'System Owner', ?, ?)
				on conflict do nothing""", OWNER_EMAIL, encoder.encode(OWNER_PASSWORD), OWNER_TOTP_SECRET);

		String hash = encoder.encode(DEMO_PASSWORD);
		seedSchool(schools, db, "DEMO", "NNS Secondary School (Demo)", hash);
		seedSchool(schools, db, "DEMO2", "Second Demo School", hash);
		System.out.println("""

				  Local API ready on http://localhost:8080
				  School sign-in : school DEMO or DEMO2 / username admin / password %s
				  Super admin    : %s / %s / authenticator secret %s
				  Dev outbox     : http://localhost:8080/api/v1/dev/outbox (setup links, verification codes)
				""".formatted(DEMO_PASSWORD, OWNER_EMAIL, OWNER_PASSWORD, OWNER_TOTP_SECRET));
	}

	private static void seedSchool(SchoolManagementService schools, JdbcTemplate db, String code, String name,
			String passwordHash) {
		if (db.queryForObject("select count(*) from school where code = ?", Long.class, code) > 0) {
			return;
		}
		var created = schools.create(new NewSchool(code, name, "School Administrator", "admin",
				"admin@" + code.toLowerCase() + ".local.test"));
		// Demo shortcut: set a known password instead of using the setup link.
		db.update("update app_user set password_hash = ?, must_change_password = false where id = ?", passwordHash,
				created.adminSetup().userId());
	}
}
