package com.selectcode.sms.platform.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.selectcode.sms.platform.domain.PlatformUser;
import com.selectcode.sms.platform.infra.PlatformUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The staging recovery switch resets the super admin once, not on every restart (deploy). */
class SuperAdminBootstrapTest {

	private static final String EMAIL = "owner@school.test";

	private static final String RECOVERY_PASSWORD = "recovery-password-1";

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

	private SuperAdminBootstrap bootstrap(PlatformUserRepository users) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("staging");
		return new SuperAdminBootstrap(users, encoder, clock, env, EMAIL, RECOVERY_PASSWORD, "Owner", true);
	}

	private PlatformUser ownerWithAuthenticator(String password) {
		PlatformUser user = new PlatformUser(EMAIL, "Owner", encoder.encode(password));
		user.enrollTotp("JBSWY3DPEHPK3PXP", clock.instant());
		return user;
	}

	@Test
	void recoveryAppliesWhenThePasswordDiffers() {
		PlatformUserRepository users = mock(PlatformUserRepository.class);
		PlatformUser user = ownerWithAuthenticator("forgotten-password-9");
		when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
		bootstrap(users).run(null);
		assertThat(encoder.matches(RECOVERY_PASSWORD, user.getPasswordHash())).isTrue();
		assertThat(user.getTotpSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
		verify(users).save(user);
	}

	@Test
	void laterRestartsKeepTheAuthenticator() {
		PlatformUserRepository users = mock(PlatformUserRepository.class);
		// Already recovered, then the authenticator was set up again
		PlatformUser user = ownerWithAuthenticator(RECOVERY_PASSWORD);
		when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
		bootstrap(users).run(null);
		bootstrap(users).run(null);
		assertThat(user.getTotpSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
		verify(users, never()).save(any());
	}
}
