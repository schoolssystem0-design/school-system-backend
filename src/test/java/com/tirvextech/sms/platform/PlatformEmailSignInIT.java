package com.tirvextech.sms.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.shared.messaging.DevOutbox;
import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Super admin sign-in with a one-time email code (production default). */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.platform.second-factor=email")
class PlatformEmailSignInIT extends IntegrationTest {

	private static final Pattern SIGN_IN_CODE = Pattern.compile("Your sign-in code is:\\s*(\\d{6})");

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Test
	void passwordThenEmailCode() throws Exception {
		String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		TestData.superAdmin(email, "owner-password-1", null);
		outbox.clear();
		ApiClient client = new ApiClient(mvc);
		client.platformLoginStep1(email, "owner-password-1").andExpect(status().isUnauthorized());
		String code = latestSignInCode();
		client.platformLogin(email, "owner-password-1", code).andExpect(status().isOk());
		client.get("/api/v1/platform/auth/me").andExpect(status().isOk());

		client.forgetCookies();
		outbox.clear();
		client.platformLoginStep1(email, "owner-password-1").andExpect(status().isUnauthorized());
		String second = latestSignInCode();
		client.platformLogin(email, "wrong-password", second).andExpect(status().isUnauthorized());
		client.platformLogin(email, "owner-password-1", second).andExpect(status().isOk());
	}

	private String latestSignInCode() {
		for (var entry : outbox.entries()) {
			var matcher = SIGN_IN_CODE.matcher(entry.message().body());
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		throw new IllegalStateException("No platform sign-in code in the dev outbox");
	}
}