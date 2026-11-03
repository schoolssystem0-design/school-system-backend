package com.selectcode.sms.support;

import com.selectcode.sms.shared.web.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests: full application context on a real PostgreSQL. The app connects as the restricted
 * runtime role (RLS applies); Flyway migrates as the superuser, exactly like production.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

	@Autowired
	private RateLimiter rateLimiter;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
		registry.add("spring.datasource.username", () -> TestDatabase.APP_USER);
		registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
		registry.add("spring.flyway.url", TestDatabase::jdbcUrl);
		registry.add("spring.flyway.user", () -> "postgres");
		registry.add("spring.flyway.password", () -> "postgres");
		registry.add("app.db.app-user", () -> TestDatabase.APP_USER);
		registry.add("app.db.app-password", () -> TestDatabase.APP_PASSWORD);
		registry.add("app.security.cookie-secure", () -> "false");
		// Tests send queued messages explicitly (MessageOutbox.dispatchDue) so results are deterministic.
		registry.add("app.outbox.auto-dispatch", () -> "false");
	}

	/** All requests in tests come from 127.0.0.1; don't let one test's sign-ins throttle the next. */
	@BeforeEach
	void resetRateLimits() {
		rateLimiter.reset();
	}
}
