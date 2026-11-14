package com.tirvextech.sms.iam;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Saved sessions the server cannot read (e.g. from before a code rename) mean "signed out", never an error. */
@AutoConfigureMockMvc
class SessionStorageIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void anUnreadableSavedSessionIsTreatedAsSignedOut() throws Exception {
		var school = TestData.school("SES");
		TestData.admin(school, "admin", "admin-password-1");
		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.get("/api/v1/auth/me").andExpect(status().isOk());

		// Every saved attribute of the session becomes bytes no Java class can be read from
		int changed = TestDatabase.superuser().update("""
				update spring_session_attributes set attribute_bytes = decode('aced00057372000f636f6d2e6f6c642e4e6f744865726500000000000000010200007870', 'hex')
				where session_primary_id = (select primary_id from spring_session order by creation_time desc limit 1)""");
		org.assertj.core.api.Assertions.assertThat(changed).isPositive();

		admin.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		// Signing in again works as usual
		ApiClient again = new ApiClient(mvc);
		again.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		again.get("/api/v1/auth/me").andExpect(status().isOk());
	}
}
