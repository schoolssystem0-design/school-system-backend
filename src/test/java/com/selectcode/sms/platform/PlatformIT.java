package com.selectcode.sms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.Totp;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestDatabase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Super admin = system owner (QA-PLT, DECISIONS D-01a). */
@AutoConfigureMockMvc
class PlatformIT extends IntegrationTest {

	static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	@Autowired
	MockMvc mvc;

	ApiClient owner;

	String email;

	@BeforeEach
	void signInOwner() throws Exception {
		email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		TestData.superAdmin(email, "owner-password-1", SECRET);
		owner = new ApiClient(mvc);
		owner.platformLogin(email, "owner-password-1", SECRET)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SIGNED_IN"));
	}

	@Test
	void signInNeedsCorrectTotp() throws Exception {
		ApiClient other = new ApiClient(mvc);
		other.post("/api/v1/platform/auth/login", """
				{"email":"%s","password":"owner-password-1","totpCode":""}""".formatted(email))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOTP_REQUIRED"));
		other.post("/api/v1/platform/auth/login", """
				{"email":"%s","password":"owner-password-1","totpCode":"000000"}""".formatted(email))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOTP_INVALID"));
		Long failures = TestDatabase.superuser()
			.queryForObject("select count(*) from platform_login_event where email = ? and result = 'TOTP_INVALID'",
					Long.class, email);
		assertThat(failures).as("failed attempt committed despite the error").isEqualTo(1);
	}

	@Test
	void firstSignInEnrolsTheAuthenticator() throws Exception {
		String newEmail = "new-owner-" + UUID.randomUUID().toString().substring(0, 6) + "@platform.test";
		TestData.superAdmin(newEmail, "owner-password-2", null);
		ApiClient fresh = new ApiClient(mvc);
		var response = fresh.platformLogin(newEmail, "owner-password-2", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ENROL_TOTP"));
		String secret = ApiClient.json(response, "$.totpSecret");
		fresh.get("/api/v1/platform/auth/me").andExpect(status().isUnauthorized());
		fresh.post("/api/v1/platform/auth/totp/enroll", "{\"code\":\"" + Totp.codeAt(secret, Instant.now()) + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.me.email").value(newEmail));
		fresh.get("/api/v1/platform/auth/me").andExpect(status().isOk());
	}

	@Test
	void createSchoolProvisionsRolesAdminAndSetupLink() throws Exception {
		String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		var created = owner.post("/api/v1/platform/schools", """
				{"code":"%s","name":"Test Academy","adminName":"Head Admin","adminUsername":"headadmin",
				 "adminEmail":"head@test.academy"}""".formatted(code.toLowerCase()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.adminSetup.username").value("headadmin"));
		long schoolId = ((Number) ApiClient.json(created, "$.schoolId")).longValue();
		String link = ApiClient.json(created, "$.adminSetup.link");

		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("select count(*) from role where school_id = ? and system_role", Long.class,
				schoolId)).isEqualTo(9);
		assertThat(db.queryForObject("select is_main_admin from app_user where school_id = ? and username = 'headadmin'",
				Boolean.class, schoolId)).isTrue();
		assertThat(db.queryForObject("select name from school_profile where school_id = ?", String.class, schoolId))
			.isEqualTo("Test Academy");
		assertThat(db.queryForObject("select count(*) from platform_audit_log where school_id = ? and action = 'SCHOOL_CREATED'",
				Long.class, schoolId)).isEqualTo(1);

		// The administrator completes the setup link, signs in and has every permission.
		String token = link.substring(link.indexOf("token=") + 6);
		ApiClient admin = new ApiClient(mvc);
		admin.post("/api/v1/auth/setup", """
				{"token":"%s","newPassword":"a strong school phrase"}""".formatted(token))
			.andExpect(status().isNoContent());
		admin.post("/api/v1/auth/setup", """
				{"token":"%s","newPassword":"another strong phrase"}""".formatted(token))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LINK_INVALID"));
		admin.login(code, "headadmin", "a strong school phrase").andExpect(status().isOk());
		admin.get("/api/v1/auth/me")
			.andExpect(jsonPath("$.permissions").value(hasItem("iam.user.manage")))
			.andExpect(jsonPath("$.superAdmin").value(false));

		// A school administrator cannot use the super admin API.
		admin.get("/api/v1/platform/overview").andExpect(status().isForbidden());
	}

	@Test
	void duplicateSchoolCodeIsRejected() throws Exception {
		var school = TestData.school("DUP");
		owner.post("/api/v1/platform/schools", """
				{"code":"%s","name":"X","adminName":"A","adminUsername":"admin","adminEmail":"a@x.test"}"""
			.formatted(school.code())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SCHOOL_CODE_TAKEN"));
	}

	@Test
	void superAdminOpensAnySchoolWithFullAccess() throws Exception {
		var a = TestData.school("A");
		var b = TestData.school("B");
		TestData.admin(a, "admin-a", "password-aaa1");
		TestData.user(b, "clerk-b", "password-bbb1");

		// Outside any school there is no school data access.
		owner.get("/api/v1/iam/users").andExpect(status().isForbidden());

		owner.post("/api/v1/platform/active-school/" + a.id())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeSchoolCode").value(a.code()));
		owner.get("/api/v1/auth/me")
			.andExpect(jsonPath("$.superAdmin").value(true))
			.andExpect(jsonPath("$.school.code").value(a.code()))
			.andExpect(jsonPath("$.permissions").value(hasItem("school.year.correct_closed")));
		owner.get("/api/v1/iam/users")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].username").value(hasItem("admin-a")))
			.andExpect(jsonPath("$.items[*].username").value(not(hasItem("clerk-b"))));

		// The super admin can act in the school (no step-up needed) and it is audited as PLATFORM.
		owner.post("/api/v1/academics/classes", "{\"name\":\"Class 1\"}").andExpect(status().isCreated());
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("""
				select actor_type from audit_log where school_id = ? and action = 'CLASS_CREATED'""", String.class,
				a.id())).isEqualTo("PLATFORM");
		assertThat(db.queryForObject(
				"select count(*) from platform_audit_log where school_id = ? and action = 'SWITCH_SCHOOL'", Long.class,
				a.id())).isEqualTo(1);

		// Switching school shows only the other school's data.
		owner.post("/api/v1/platform/active-school/" + b.id()).andExpect(status().isOk());
		owner.get("/api/v1/iam/users")
			.andExpect(jsonPath("$.items[*].username").value(hasItem("clerk-b")))
			.andExpect(jsonPath("$.items[*].username").value(not(hasItem("admin-a"))));

		owner.delete("/api/v1/platform/active-school").andExpect(status().isOk());
		owner.get("/api/v1/iam/users").andExpect(status().isForbidden());
	}

	@Test
	void suspendingASchoolEndsItsSessionsAndBlocksSignIn() throws Exception {
		var school = TestData.school("SUS");
		TestData.admin(school, "admin", "password-sus1");
		ApiClient user = new ApiClient(mvc);
		user.login(school.code(), "admin", "password-sus1").andExpect(status().isOk());
		user.get("/api/v1/auth/me").andExpect(status().isOk());

		owner.post("/api/v1/platform/schools/" + school.id() + "/suspend", "{\"reason\":\"Unpaid subscription\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));

		user.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ApiClient(mvc).login(school.code(), "admin", "password-sus1")
			.andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("SCHOOL_SUSPENDED"));

		owner.post("/api/v1/platform/schools/" + school.id() + "/reactivate").andExpect(status().isOk());
		new ApiClient(mvc).login(school.code(), "admin", "password-sus1").andExpect(status().isOk());
	}

	@Test
	void overviewListsEverySchoolWithItsOwnCounts() throws Exception {
		var a = TestData.school("OVA");
		var b = TestData.school("OVB");
		TestData.user(a, "u1", "password-ova1");
		TestData.user(a, "u2", "password-ova2");
		TestData.user(b, "u3", "password-ovb1");
		var response = owner.get("/api/v1/platform/overview").andExpect(status().isOk());
		java.util.List<Number> usersA = ApiClient.json(response, "$.rows[?(@.code=='" + a.code() + "')].users");
		java.util.List<Number> usersB = ApiClient.json(response, "$.rows[?(@.code=='" + b.code() + "')].users");
		assertThat(usersA.getFirst().longValue()).isEqualTo(2);
		assertThat(usersB.getFirst().longValue()).isEqualTo(1);
	}

	@Test
	void domainsAreMappedOncePerHost() throws Exception {
		var school = TestData.school("DOM");
		String host = "www." + school.code().toLowerCase() + ".edu.pk";
		owner.post("/api/v1/platform/schools/" + school.id() + "/domains", "{\"host\":\"" + host + "\",\"primary\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.domains[0].host").value(host));
		owner.post("/api/v1/platform/schools/" + school.id() + "/domains", "{\"host\":\"" + host.toUpperCase() + "\"}")
			.andExpect(status().isConflict());
		owner.post("/api/v1/platform/schools/" + school.id() + "/domains", "{\"host\":\"not a host\"}")
			.andExpect(status().isUnprocessableContent());
	}
}
