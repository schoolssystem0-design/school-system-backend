package com.selectcode.sms.shared.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * After every migration, makes sure the runtime LOGIN role exists, has the configured password and is a member of
 * {@code sms_app}. Runs on Flyway's (owner) connection. The runtime role is never superuser and never owns tables, so
 * row-level security always applies to it.
 */
@Component
@EnableConfigurationProperties(AppDatabaseRoleCallback.DbRoleProperties.class)
class AppDatabaseRoleCallback implements Callback {

	@ConfigurationProperties("app.db")
	record DbRoleProperties(String appUser, String appPassword) {
	}

	private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

	private static final String ENSURE_ROLE = """
			DO $$
			DECLARE
			    role_name text := current_setting('sms.app_user');
			    role_password text := current_setting('sms.app_password');
			BEGIN
			    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
			        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L',
			                       role_name, role_password);
			    ELSE
			        EXECUTE format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', role_name, role_password);
			    END IF;
			    EXECUTE format('GRANT sms_app TO %I', role_name);
			END
			$$""";

	private final DbRoleProperties properties;

	AppDatabaseRoleCallback(DbRoleProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.AFTER_MIGRATE && StringUtils.hasText(properties.appUser());
	}

	@Override
	public boolean canHandleInTransaction(Event event, Context context) {
		return true;
	}

	@Override
	public void handle(Event event, Context context) {
		String user = properties.appUser();
		if (!ROLE_NAME.matcher(user).matches()) {
			throw new IllegalStateException("app.db.app-user must be a lowercase PostgreSQL identifier");
		}
		if (!StringUtils.hasText(properties.appPassword())) {
			throw new IllegalStateException("app.db.app-password must be set when app.db.app-user is set");
		}
		try {
			Connection connection = context.getConnection();
			try (PreparedStatement settings = connection
				.prepareStatement("select set_config('sms.app_user', ?, true), set_config('sms.app_password', ?, true)")) {
				settings.setString(1, user);
				settings.setString(2, properties.appPassword());
				settings.execute();
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute(ENSURE_ROLE);
				statement.execute("select set_config('sms.app_password', '', true)");
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not create or update the application database role", ex);
		}
	}

	@Override
	public String getCallbackName() {
		return "app-database-role";
	}
}
