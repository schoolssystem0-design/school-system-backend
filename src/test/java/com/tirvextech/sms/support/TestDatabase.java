package com.selectcode.sms.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One real PostgreSQL server (embedded binaries, no Docker) shared by all tests in the JVM. Never H2: RLS, roles and
 * PostgreSQL-specific SQL must be tested for real.
 */
public final class TestDatabase {

	public static final String APP_USER = "sms_app_login";

	public static final String APP_PASSWORD = "test-app-password";

	private static EmbeddedPostgres postgres;

	private TestDatabase() {
	}

	public static synchronized EmbeddedPostgres instance() {
		if (postgres == null) {
			try {
				postgres = EmbeddedPostgres.builder().start();
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Could not start embedded PostgreSQL", ex);
			}
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					postgres.close();
				}
				catch (IOException ignored) {
					// JVM is exiting
				}
			}));
		}
		return postgres;
	}

	public static String jdbcUrl() {
		return "jdbc:postgresql://localhost:" + instance().getPort() + "/postgres";
	}

	/** Superuser connection: bypasses RLS. Use only to arrange test data or inspect raw state. */
	public static DataSource superuserDataSource() {
		return instance().getPostgresDatabase();
	}

	public static JdbcTemplate superuser() {
		return new JdbcTemplate(superuserDataSource());
	}
}
