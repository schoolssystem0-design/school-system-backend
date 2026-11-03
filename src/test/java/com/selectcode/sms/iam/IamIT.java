package com.selectcode.sms.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Users, roles, permissions, step-up, passwords (QA-SEC). */
@AutoConfigureMockMvc
class IamIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	SeededSchool school;

	ApiClient admin;

	long adminRoleId;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("IAM");
		TestData.admin(school, "admin", "admin-password-1");
		adminRoleId = TestDatabase.superuser()
			.queryForObject("select id from role where school_id = ? and code = 'ADMIN'", Long.class, school.id());
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	@Test
	void permissionsAreEnforcedOnTheApi() throws Exception {
		// QA-SEC-010/011: a teacher-like role cannot reach user management.
		TestData.userWithPermissions(school, "teacher", "teacher-pass-1", Permissions.ACADEMICS_VIEW);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "teacher", "teacher-pass-1").andExpect(status().isOk());
		teacher.get("/api/v1/auth/me").andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.contains("academics.view")));
		teacher.get("/api/v1/iam/users").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		teacher.get("/api/v1/audit").andExpect(status().isForbidden());
		teacher.get("/api/v1/academics/classes").andExpect(status().isOk());
		teacher.post("/api/v1/academics/classes", "{\"name\":\"Class 9\"}").andExpect(status().isForbidden());
		admin.get("/api/v1/iam/users").andExpect(status().isOk());
	}

	@Test
	void sensitiveActionsNeedStepUp() throws Exception {
		String body = """
				{"username":"accountant","displayName":"Ali Accounts","email":"ali@school.test","roleIds":[%d]}"""
			.formatted(adminRoleId);
		admin.post("/api/v1/iam/users", body)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
		admin.post("/api/v1/auth/step-up/verify", "{\"code\":\"000000\"}")
			.andExpect(status().isUnprocessableContent());
		admin.stepUp(outbox);
		admin.get("/api/v1/auth/me").andExpect(jsonPath("$.stepUpUntil").isNotEmpty());
		admin.post("/api/v1/iam/users", body)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.username").value("accountant"))
			.andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
	}

	@Test
	void newUserMustChangeTheTemporaryPassword() throws Exception {
		admin.stepUp(outbox);
		var created = admin.post("/api/v1/iam/users", """
				{"username":"clerk","displayName":"Office Clerk","roleIds":[%d]}""".formatted(adminRoleId))
			.andExpect(status().isCreated());
		String temporary = ApiClient.json(created, "$.temporaryPassword");

		ApiClient clerk = new ApiClient(mvc);
		clerk.login(school.code(), "clerk", temporary).andExpect(status().isOk());
		clerk.get("/api/v1/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));
		clerk.get("/api/v1/academics/classes")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
		clerk.post("/api/v1/auth/change-password", """
				{"currentPassword":"%s","newPassword":"short"}""".formatted(temporary))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
		clerk.post("/api/v1/auth/change-password", """
				{"currentPassword":"%s","newPassword":"my new office phrase"}""".formatted(temporary))
			.andExpect(status().isNoContent());
		clerk.get("/api/v1/academics/classes").andExpect(status().isOk());
	}

	@Test
	void deactivationEndsSessionsImmediately() throws Exception {
		long clerkId = TestData.user(school, "leaver", "leaver-pass-1");
		ApiClient leaver = new ApiClient(mvc);
		leaver.login(school.code(), "leaver", "leaver-pass-1").andExpect(status().isOk());
		leaver.get("/api/v1/auth/me").andExpect(status().isOk());

		admin.stepUp(outbox);
		admin.post("/api/v1/iam/users/" + clerkId + "/deactivate", "{\"reason\":\"Resigned\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("INACTIVE"));

		leaver.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ApiClient(mvc).login(school.code(), "leaver", "leaver-pass-1").andExpect(status().isUnauthorized());
		assertThat(TestDatabase.superuser()
			.queryForObject("select reason from account_status_change where user_id = ?", String.class, clerkId))
			.isEqualTo("Resigned");
	}

	@Test
	void mainAdministratorCannotBeDeactivated() throws Exception {
		long mainId = TestDatabase.superuser()
			.queryForObject("select id from app_user where school_id = ? and username = 'admin'", Long.class, school.id());
		TestDatabase.superuser().update("update app_user set is_main_admin = true where id = ?", mainId);
		admin.stepUp(outbox);
		admin.post("/api/v1/iam/users/" + mainId + "/deactivate", "{\"reason\":\"test\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("MAIN_ADMIN"));
	}

	@Test
	void roleChangesApplyWithoutSigningInAgain() throws Exception {
		TestData.userWithPermissions(school, "coord", "coord-pass-12", Permissions.ACADEMICS_VIEW);
		long roleId = TestDatabase.superuser()
			.queryForObject("select id from role where school_id = ? and code = 'R_COORD'", Long.class, school.id());
		ApiClient coord = new ApiClient(mvc);
		coord.login(school.code(), "coord", "coord-pass-12").andExpect(status().isOk());
		coord.get("/api/v1/iam/users").andExpect(status().isForbidden());

		admin.stepUp(outbox);
		admin.put("/api/v1/iam/roles/" + roleId, """
				{"name":"Coordinator","permissions":["academics.view","iam.user.view"]}""").andExpect(status().isOk());
		coord.get("/api/v1/iam/users").andExpect(status().isOk());

		admin.put("/api/v1/iam/roles/" + adminRoleId, "{\"name\":\"x\",\"permissions\":[]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
	}

	@Test
	void passwordResetByEmailLink() throws Exception {
		outbox.clear();
		ApiClient anonymous = new ApiClient(mvc);
		anonymous.post("/api/v1/auth/password-reset/request", """
				{"schoolCode":"%s","username":"admin"}""".formatted(school.code())).andExpect(status().isAccepted());
		// Unknown users get the same answer and no email.
		anonymous.post("/api/v1/auth/password-reset/request", """
				{"schoolCode":"%s","username":"nobody"}""".formatted(school.code())).andExpect(status().isAccepted());
		assertThat(outbox.entries()).hasSize(1);
		String body = outbox.entries().getFirst().message().body();
		String token = body.substring(body.indexOf("token=") + 6).split("\\s")[0];

		anonymous.post("/api/v1/auth/password-reset/confirm", """
				{"token":"%s","newPassword":"recovered school phrase"}""".formatted(token))
			.andExpect(status().isNoContent());
		// Existing sessions end after a reset.
		admin.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ApiClient(mvc).login(school.code(), "admin", "recovered school phrase").andExpect(status().isOk());
		new ApiClient(mvc).login(school.code(), "admin", "admin-password-1").andExpect(status().isUnauthorized());
	}

	@Test
	void loginHistoryAndActivityAreSearchable() throws Exception {
		new ApiClient(mvc).login(school.code(), "admin", "wrong-password").andExpect(status().isUnauthorized());
		admin.get("/api/v1/iam/login-history?result=INVALID")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].username").value("admin"))
			.andExpect(jsonPath("$.items[0].result").value("INVALID"));
		admin.post("/api/v1/academics/subjects", "{\"name\":\"Mathematics\"}").andExpect(status().isCreated());
		admin.get("/api/v1/audit?q=subject")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].action").value(hasItem("SUBJECT_CREATED")))
			.andExpect(jsonPath("$.items[0].actorName").value("User admin"));
	}
}
