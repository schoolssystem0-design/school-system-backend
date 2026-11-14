package com.tirvextech.sms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.shared.messaging.DevOutbox;
import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 14-day free trial: sign-up, one per internet address and email, ending, converting and extending. */
@AutoConfigureMockMvc
class TrialIT extends IntegrationTest {

	static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	ApiClient owner;

	@BeforeEach
	void signInOwner() throws Exception {
		String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		TestData.superAdmin(email, "owner-password-1", SECRET);
		owner = new ApiClient(mvc);
		owner.platformLogin(email, "owner-password-1", SECRET).andExpect(status().isOk());
		outbox.clear();
	}

	static String randomIp() {
		var r = ThreadLocalRandom.current();
		return "10." + r.nextInt(1, 255) + "." + r.nextInt(0, 255) + "." + r.nextInt(1, 255);
	}

	static String code() {
		return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	static String email() {
		return "head-" + UUID.randomUUID().toString().substring(0, 8) + "@school.test";
	}

	static String body(String code, String email) {
		return """
				{"schoolName":"Trial School %s","schoolCode":"%s","adminName":"Head Teacher","username":"head","email":"%s"}"""
			.formatted(code, code, email);
	}

	ApiClient visitor(String ip) {
		return new ApiClient(mvc).from(ip);
	}

	/** The setup link sent to this address; the admin chooses a password with it. */
	void setPassword(String email, String password) throws Exception {
		String link = outbox.entries().stream()
			.filter(e -> e.message().recipient().equals(email) && e.message().subject().startsWith("Set up your account"))
			.findFirst().orElseThrow().message().body();
		Matcher token = Pattern.compile("token=([A-Za-z0-9_.-]+)").matcher(link);
		assertThat(token.find()).isTrue();
		new ApiClient(mvc).post("/api/v1/auth/setup", """
				{"token":"%s","newPassword":"%s"}""".formatted(token.group(1), password)).andExpect(status().isNoContent());
	}

	long schoolId(String code) {
		return TestDatabase.superuser().queryForObject("select id from school where code = ?", Long.class, code);
	}

	@Test
	void aSchoolStartsATrialAndSignsIn() throws Exception {
		visitor(randomIp()).get("/api/v1/public/trial").andExpect(jsonPath("$.days").value(14));
		String code = code(), email = email();
		var r = visitor(randomIp()).post("/api/v1/public/trial", body(code, email.toUpperCase()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.schoolCode").value(code))
			.andExpect(jsonPath("$.username").value("head"))
			.andExpect(jsonPath("$.email").value(email));
		Instant ends = Instant.parse(ApiClient.json(r, "$.trialEndsAt"));
		assertThat(Duration.between(Instant.now(), ends).toDays()).isBetween(13L, 14L);
		// No setup link in the answer: only the email can be used to choose the password
		assertThat(r.andReturn().getResponse().getContentAsString()).doesNotContain("token");
		assertThat(outbox.entries()).anyMatch(e -> e.message().recipient().equals(email)
				&& e.message().subject().equals("Your free trial of Tirvex Tech School Management System") && e.message().body().contains(code));

		setPassword(email, "trial-password-1");
		ApiClient head = new ApiClient(mvc);
		head.login(code, "head", "trial-password-1").andExpect(status().isOk());
		head.get("/api/v1/auth/me").andExpect(jsonPath("$.school.trialEndsAt").isNotEmpty());
		owner.get("/api/v1/platform/overview")
			.andExpect(jsonPath("$.rows[?(@.code=='" + code + "')].trialEndsAt").isNotEmpty());
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from platform_audit_log where action = 'TRIAL_STARTED' and school_id = ?", Long.class,
				schoolId(code))).isEqualTo(1);
	}

	@Test
	void oneTrialPerInternetAddressAndPerEmail() throws Exception {
		String ip = randomIp(), email = email();
		visitor(ip).post("/api/v1/public/trial", body(code(), email)).andExpect(status().isCreated());
		// Same connection, another school and email
		visitor(ip).post("/api/v1/public/trial", body(code(), email()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIAL_USED"));
		// Same email from another connection
		visitor(randomIp()).post("/api/v1/public/trial", body(code(), email))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIAL_EMAIL_USED"));
		// IPv6: every address of one home/office network counts as the same connection
		String net = "2001:db8:" + Integer.toHexString(ThreadLocalRandom.current().nextInt(1, 0xffff)) + ":1";
		visitor(net + "::10").post("/api/v1/public/trial", body(code(), email())).andExpect(status().isCreated());
		visitor(net + ":abcd:ef01:2345:6789").post("/api/v1/public/trial", body(code(), email()))
			.andExpect(jsonPath("$.code").value("TRIAL_USED"));
	}

	@Test
	void aFailedSignUpDoesNotUseUpTheTrial() throws Exception {
		String ip = randomIp();
		String taken = code();
		visitor(randomIp()).post("/api/v1/public/trial", body(taken, email())).andExpect(status().isCreated());
		visitor(ip).post("/api/v1/public/trial", body(taken, email()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHOOL_CODE_TAKEN"));
		visitor(ip).post("/api/v1/public/trial", body("x", email())).andExpect(jsonPath("$.code").value("SCHOOL_CODE_INVALID"));
		visitor(ip).post("/api/v1/public/trial", body(code(), "not-an-email")).andExpect(jsonPath("$.code").value("EMAIL_INVALID"));
		// The same connection can still start its one trial
		visitor(ip).post("/api/v1/public/trial", body(code(), email())).andExpect(status().isCreated());
		// Bots that fill the hidden field get nothing
		visitor(randomIp()).post("/api/v1/public/trial", body(code(), email()).replace("}", ",\"website\":\"spam\"}"))
			.andExpect(jsonPath("$.code").value("TRIAL_REFUSED"));
	}

	@Test
	void anEndedTrialBlocksSignInUntilTheOwnerActsAndKeepsTheData() throws Exception {
		String code = code(), email = email();
		visitor(randomIp()).post("/api/v1/public/trial", body(code, email)).andExpect(status().isCreated());
		setPassword(email, "trial-password-2");
		long id = schoolId(code);
		ApiClient head = new ApiClient(mvc);
		head.login(code, "head", "trial-password-2").andExpect(status().isOk());
		head.post("/api/v1/academics/classes", "{\"name\":\"Class 1\"}").andExpect(status().is2xxSuccessful());

		TestDatabase.superuser().update("update school set trial_ends_at = now() - interval '1 minute' where id = ?", id);
		// Signed-in users are out at their next click; nobody can sign in
		head.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ApiClient(mvc).login(code, "head", "trial-password-2")
			.andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("TRIAL_ENDED"));
		owner.get("/api/v1/platform/schools/" + id).andExpect(jsonPath("$.trialEndsAt").isNotEmpty());

		// The owner gives 7 more days (counted from today)
		var extended = owner.post("/api/v1/platform/schools/" + id + "/trial/extend", "{\"days\":7}").andExpect(status().isOk());
		Instant until = Instant.parse(ApiClient.json(extended, "$.trialEndsAt"));
		assertThat(Duration.between(Instant.now(), until).toDays()).isBetween(6L, 7L);
		owner.post("/api/v1/platform/schools/" + id + "/trial/extend", "{\"days\":0}").andExpect(status().isUnprocessableContent());
		ApiClient again = new ApiClient(mvc);
		again.login(code, "head", "trial-password-2").andExpect(status().isOk());
		// Their data is still there
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from school_class where school_id = ?", Long.class, id))
			.isEqualTo(1);

		// The school buys: full access, no end date
		owner.post("/api/v1/platform/schools/" + id + "/trial/end").andExpect(status().isOk())
			.andExpect(jsonPath("$.trialEndsAt").doesNotExist());
		again.get("/api/v1/auth/me").andExpect(jsonPath("$.school.trialEndsAt").doesNotExist());
		owner.post("/api/v1/platform/schools/" + id + "/trial/end").andExpect(jsonPath("$.code").value("NOT_ON_TRIAL"));
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from platform_audit_log where school_id = ? and action in ('TRIAL_EXTENDED', 'TRIAL_CONVERTED')",
				Long.class, id)).isEqualTo(2);
	}

	@Test
	void deletingATrialSchoolDoesNotGiveTheAddressAnotherTrial() throws Exception {
		String ip = randomIp(), code = code();
		visitor(ip).post("/api/v1/public/trial", body(code, email())).andExpect(status().isCreated());
		long id = schoolId(code);
		owner.post("/api/v1/platform/schools/" + id + "/delete", "{\"confirmCode\":\"" + code + "\",\"reason\":\"Trial over\"}")
			.andExpect(status().isNoContent());
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from trial_signup where ip = ? and created_school_id is null", Long.class, ip)).isEqualTo(1);
		visitor(ip).post("/api/v1/public/trial", body(code(), email())).andExpect(jsonPath("$.code").value("TRIAL_USED"));
	}

	@Test
	void ownerCreatedSchoolsAreNotTrials() throws Exception {
		var school = TestData.school("FULL");
		TestData.admin(school, "admin", "password-full1");
		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "password-full1").andExpect(status().isOk());
		admin.get("/api/v1/auth/me").andExpect(jsonPath("$.school.trialEndsAt").doesNotExist());
		owner.post("/api/v1/platform/schools/" + school.id() + "/trial/extend", "{\"days\":5}")
			.andExpect(jsonPath("$.code").value("NOT_ON_TRIAL"));
	}
}
