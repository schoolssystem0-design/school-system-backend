package com.selectcode.sms.platform.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which addresses count as "the same internet connection" for the one-trial rule. */
class TrialServiceTest {

	@Test
	void ipv4IsTheAddressItself() {
		assertThat(TrialService.network("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(TrialService.network(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
	}

	@Test
	void ipv6CountsTheWholeSlash64Network() {
		String a = TrialService.network("2001:db8:aa:1:1111:2222:3333:4444");
		String b = TrialService.network("2001:0db8:00aa:0001::9");
		assertThat(a).isEqualTo(b).endsWith("/64");
		assertThat(TrialService.network("2001:db8:aa:2::9")).isNotEqualTo(a);
		assertThat(TrialService.network("[2001:db8:aa:1::5]")).isEqualTo(a);
	}

	@Test
	void ipv4MappedIpv6IsTheIpv4Address() {
		assertThat(TrialService.network("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
	}
}
