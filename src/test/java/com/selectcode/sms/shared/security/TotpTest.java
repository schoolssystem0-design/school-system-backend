package com.selectcode.sms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

	/** RFC 6238 appendix B test secret (SHA-1): ASCII "12345678901234567890". */
	private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@Test
	void matchesRfc6238TestVectors() {
		// RFC values are 8 digits; our 6-digit codes are their last 6 digits.
		assertThat(Totp.generate(RFC_KEY, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.generate(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.generate(RFC_KEY, 1234567890L / 30)).isEqualTo("005924");
		assertThat(Totp.generate(RFC_KEY, 2000000000L / 30)).isEqualTo("279037");
	}

	@Test
	void base32RoundTripAndVerifyWithClockDrift() {
		String secret = Totp.Base32.encode(RFC_KEY);
		assertThat(secret).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
		Instant now = Instant.ofEpochSecond(1111111109L);
		String code = Totp.codeAt(secret, now);
		assertThat(Totp.verify(secret, code, now)).isTrue();
		assertThat(Totp.verify(secret, code, now.plusSeconds(30))).isTrue(); // one step drift
		assertThat(Totp.verify(secret, code, now.plusSeconds(90))).isFalse();
		assertThat(Totp.verify(secret, "12345", now)).isFalse();
		assertThat(Totp.verify(secret, null, now)).isFalse();
	}

	@Test
	void newSecretsAreRandomAndUsable() {
		String a = Totp.newSecret();
		assertThat(a).hasSize(32).isNotEqualTo(Totp.newSecret());
		assertThat(Totp.otpauthUri("School Platform", "owner@example.com", a))
			.startsWith("otpauth://totp/School%20Platform:owner%40example.com?secret=" + a);
	}
}
