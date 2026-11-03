package com.selectcode.sms.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RailwayDatabaseEnvironmentPostProcessorTest {

	@Test
	void parsesStandardRailwayUrl() {
		var parsed = RailwayDatabaseEnvironmentPostProcessor.parsePostgresUrl(
				"postgresql://postgres:secret@postgres.railway.internal:5432/railway");
		assertThat(parsed.host()).isEqualTo("postgres.railway.internal");
		assertThat(parsed.port()).isEqualTo(5432);
		assertThat(parsed.database()).isEqualTo("railway");
		assertThat(parsed.user()).isEqualTo("postgres");
		assertThat(parsed.password()).isEqualTo("secret");
	}

	@Test
	void decodesUrlEncodedCredentials() {
		var parsed = RailwayDatabaseEnvironmentPostProcessor.parsePostgresUrl(
				"postgres://user%40mail:p%40ss%2Fword@host.example/db");
		assertThat(parsed.user()).isEqualTo("user@mail");
		assertThat(parsed.password()).isEqualTo("p@ss/word");
	}

	@Test
	void rejectsMissingHost() {
		assertThatThrownBy(() -> RailwayDatabaseEnvironmentPostProcessor.parsePostgresUrl("postgresql://u:p@/db"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
