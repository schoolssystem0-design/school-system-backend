package com.selectcode.sms.shared.persistence;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Maps Railway Postgres connection variables into {@code PGHOST}, {@code PGPORT}, etc. when the backend service
 * references {@code DATABASE_PRIVATE_URL} or {@code DATABASE_URL} but not individual {@code PG*} variables.
 */
public class RailwayDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final String PROPERTY_SOURCE = "railwayDatabase";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> props = new HashMap<>();

		if (!StringUtils.hasText(environment.getProperty("SPRING_PROFILES_ACTIVE"))
				&& StringUtils.hasText(environment.getProperty("RAILWAY_ENVIRONMENT"))) {
			String railwayEnv = environment.getProperty("RAILWAY_ENVIRONMENT");
			props.put("SPRING_PROFILES_ACTIVE", "production".equalsIgnoreCase(railwayEnv) ? "prod" : "staging");
		}

		if (isUsableHost(environment.getProperty("PGHOST"))) {
			mergeIfNeeded(environment, props);
			return;
		}

		String dbUrl = firstNonBlank(environment.getProperty("DATABASE_PRIVATE_URL"),
				environment.getProperty("DATABASE_URL"), environment.getProperty("SPRING_DATASOURCE_URL"));
		if (!StringUtils.hasText(dbUrl)) {
			mergeIfNeeded(environment, props);
			return;
		}

		PostgresUrl parsed = parsePostgresUrl(dbUrl);
		applyPostgres(environment, props, parsed);
		mergeIfNeeded(environment, props);
	}

	private static void applyPostgres(ConfigurableEnvironment environment, Map<String, Object> props,
			PostgresUrl parsed) {
		String jdbcUrl = toJdbcUrl(parsed);
		props.put("PGHOST", parsed.host());
		props.put("PGPORT", String.valueOf(parsed.port()));
		props.put("PGDATABASE", parsed.database());
		props.put("PGUSER", parsed.user());
		props.put("PGPASSWORD", parsed.password());
		props.put("spring.datasource.url", jdbcUrl);
		props.put("spring.flyway.url", jdbcUrl);
		if (!StringUtils.hasText(environment.getProperty("DB_MIGRATION_USER"))) {
			props.put("DB_MIGRATION_USER", parsed.user());
		}
		if (!StringUtils.hasText(environment.getProperty("DB_MIGRATION_PASSWORD"))) {
			props.put("DB_MIGRATION_PASSWORD", parsed.password());
		}
	}

	static boolean isUsableHost(String pgHost) {
		if (!StringUtils.hasText(pgHost)) {
			return false;
		}
		if ("localhost".equalsIgnoreCase(pgHost) || "127.0.0.1".equals(pgHost)) {
			return false;
		}
		// Unresolved Railway reference syntax ends up in the container literally
		return !pgHost.contains("${{");
	}

	static String toJdbcUrl(PostgresUrl parsed) {
		return "jdbc:postgresql://" + parsed.host() + ":" + parsed.port() + "/" + parsed.database();
	}

	private static void mergeIfNeeded(ConfigurableEnvironment environment, Map<String, Object> props) {
		if (props.isEmpty()) {
			return;
		}
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, props));
	}

	private static String firstNonBlank(String first, String second) {
		if (StringUtils.hasText(first)) {
			return first;
		}
		return StringUtils.hasText(second) ? second : null;
	}

	static PostgresUrl parsePostgresUrl(String dbUrl) {
		String normalized = dbUrl.trim();
		if (normalized.startsWith("jdbc:postgresql://") || normalized.startsWith("jdbc:postgres://")) {
			normalized = normalized.substring("jdbc:".length());
		}
		int schemeEnd = normalized.indexOf("://");
		if (schemeEnd < 0) {
			throw new IllegalArgumentException("Database URL must include a scheme (postgresql:// or postgres://)");
		}
		String scheme = normalized.substring(0, schemeEnd);
		if (!scheme.equals("postgres") && !scheme.equals("postgresql")) {
			throw new IllegalArgumentException("Unsupported database URL scheme: " + scheme);
		}

		String remainder = normalized.substring(schemeEnd + 3);
		int at = remainder.lastIndexOf('@');
		if (at < 0) {
			throw new IllegalArgumentException("Database URL must include user credentials");
		}

		String userInfo = remainder.substring(0, at);
		String hostPart = remainder.substring(at + 1);
		int colon = userInfo.indexOf(':');
		if (colon < 0) {
			throw new IllegalArgumentException("Database URL must include a password");
		}
		String user = decode(userInfo.substring(0, colon));
		String password = decode(userInfo.substring(colon + 1));

		String host;
		int port;
		String database;
		int slash = hostPart.indexOf('/');
		String hostPort = slash >= 0 ? hostPart.substring(0, slash) : hostPart;
		String path = slash >= 0 ? hostPart.substring(slash + 1) : "";
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		if (!StringUtils.hasText(path)) {
			throw new IllegalArgumentException("Database URL must include a database name");
		}
		database = decode(path);

		if (hostPort.startsWith("[")) {
			int closing = hostPort.indexOf(']');
			if (closing < 0) {
				throw new IllegalArgumentException("Invalid IPv6 host in database URL");
			}
			host = hostPort.substring(1, closing);
			if (hostPort.length() > closing + 1) {
				if (!hostPort.startsWith("]:", closing)) {
					throw new IllegalArgumentException("Invalid host/port in database URL");
				}
				port = Integer.parseInt(hostPort.substring(closing + 2));
			}
			else {
				port = 5432;
			}
		}
		else {
			int portSep = hostPort.lastIndexOf(':');
			if (portSep >= 0) {
				host = hostPort.substring(0, portSep);
				port = Integer.parseInt(hostPort.substring(portSep + 1));
			}
			else {
				host = hostPort;
				port = 5432;
			}
		}

		if (!StringUtils.hasText(host)) {
			throw new IllegalArgumentException("Database URL must include a host");
		}

		return new PostgresUrl(host, port, database, user, password);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	record PostgresUrl(String host, int port, String database, String user, String password) {
	}
}
