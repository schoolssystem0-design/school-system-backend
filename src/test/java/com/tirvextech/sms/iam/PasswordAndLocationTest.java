package com.selectcode.sms.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.selectcode.sms.iam.domain.LoginEvent;
import com.selectcode.sms.iam.domain.PasswordPolicy;
import com.selectcode.sms.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class PasswordAndLocationTest {

	@Test
	void passwordPolicy() {
		PasswordPolicy.check("a long school phrase", "ahmed");
		assertThatThrownBy(() -> PasswordPolicy.check("short", "x")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "PASSWORD_TOO_SHORT");
		assertThatThrownBy(() -> PasswordPolicy.check("Pakistan123", "x"))
			.hasFieldOrPropertyWithValue("code", "PASSWORD_TOO_COMMON");
		assertThatThrownBy(() -> PasswordPolicy.check("aaaaaaaaaaaa", "x"))
			.hasFieldOrPropertyWithValue("code", "PASSWORD_TOO_COMMON");
		assertThatThrownBy(() -> PasswordPolicy.check("ahmed-is-great-2026", "Ahmed"))
			.hasFieldOrPropertyWithValue("code", "PASSWORD_CONTAINS_USERNAME");
	}

	@Test
	void temporaryPasswordsAreLongAndUnambiguous() {
		String temp = PasswordPolicy.temporary();
		assertThat(temp).hasSize(12).doesNotContain("0", "O", "1", "l", "I");
		PasswordPolicy.check(temp, "someone");
	}

	@Test
	void networkPrefixForNewLocationAlerts() {
		assertThat(LoginEvent.prefixOf("39.42.10.7")).isEqualTo("39.42.10.0/24");
		assertThat(LoginEvent.prefixOf("2400:adc1:1c4:5900::1")).isEqualTo("2400:adc1:1c4::/48");
		assertThat(LoginEvent.prefixOf(null)).isNull();
	}
}
