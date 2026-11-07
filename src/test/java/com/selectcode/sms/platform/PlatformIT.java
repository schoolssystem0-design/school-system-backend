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
	void emailSetUpCanBeCheckedAndTested() throws Exception {
		owner.get("/api/v1/platform/email").andExpect(status().isOk())
			.andExpect(jsonPath("$.ready").value(true))
			.andExpect(jsonPath("$.problems.length()").value(0));
		owner.post("/api/v1/platform/email/test", "{\"to\":\"not-an-email\"}").andExpect(status().is4xxClientError());
		owner.post("/api/v1/platform/email/test", "{\"to\":\"me@platform.test\"}").andExpect(jsonPath("$.sent").value(true));
		// School users cannot see the server's email set-up
		new ApiClient(mvc).get("/api/v1/platform/email").andExpect(status().isUnauthorized());
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
	void deletingASchoolRemovesAllItsDataAndNothingElse() throws Exception {
		var school = TestData.school("DEL");
		TestData.admin(school, "admin", "password-del1");
		var other = TestData.school("KEEP");
		TestData.admin(other, "admin", "password-keep1");
		long id = school.id();
		var db = TestDatabase.superuser();

		// A school in use: year, class, students and families, attendance, test marks sent by SMS, fees and a payment
		var admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "password-del1").andExpect(status().isOk());
		long year = TestData.yearAroundToday(school, true);
		long class5 = TestData.schoolClass(school, "Class 5", 5);
		long sec5a = TestData.section(school, class5, "A");
		long maths = TestData.subject(school, "Mathematics");
		var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Karachi"));
		long[] kids = new long[2];
		for (int i = 0; i < kids.length; i++) {
			var r = admin.post("/api/v1/students", """
					{"student":{"name":"Child %d","gender":"MALE"},"newFamily":{"fatherName":"Father %d","fatherPhone":"0300-123456%d"},
					 "sectionId":%d,"admissionDate":"%s"}""".formatted(i, i, i, sec5a, today.minusDays(20)))
				.andExpect(status().isCreated());
			kids[i] = ((Number) ApiClient.json(r, "$.id")).longValue();
		}
		admin.put("/api/v1/attendance/registers/" + sec5a + "?date=" + today.minusDays(1),
				"{\"marks\":[{\"studentId\":%d,\"status\":\"A\"}]}".formatted(kids[0]));
		var t = admin.post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"Monthly test","testDate":"%s","maxMarks":"25"}"""
			.formatted(sec5a, maths, today.minusDays(1))).andExpect(status().isCreated());
		long test = ((Number) ApiClient.json(t, "$.test.id")).longValue();
		admin.put("/api/v1/test-marks/" + test + "/marks", "{\"marks\":[{\"studentId\":%d,\"obtained\":\"20\"}]}".formatted(kids[1]))
			.andExpect(status().isOk());
		admin.post("/api/v1/test-marks/" + test + "/sms").andExpect(status().isOk());
		var h = admin.post("/api/v1/fees/heads", "{\"name\":\"Tuition\",\"frequency\":\"MONTHLY\",\"refundable\":false}")
			.andExpect(status().isCreated());
		long tuition = ((Number) ApiClient.json(h, "$.id")).longValue();
		admin.put("/api/v1/fees/years/" + year + "/class-amounts",
				"[{\"classId\":%d,\"feeHeadId\":%d,\"amount\":\"3500\"}]".formatted(class5, tuition)).andExpect(status().isOk());
		admin.postWithKey("/api/v1/fees/bill-runs", "{\"period\":\"%s\",\"headIds\":[%d],\"issueDate\":\"%s\"}"
			.formatted(java.time.YearMonth.from(today), tuition, today), "run-1").andExpect(status().is2xxSuccessful());
		long family = ((Number) ApiClient.json(admin.get("/api/v1/students/" + kids[0]), "$.family.id")).longValue();
		long cash = db.queryForObject("select id from ledger_account where school_id = ? and subtype = 'CASH'", Long.class, id);
		admin.postWithKey("/api/v1/fees/payments", "{\"familyId\":%d,\"amount\":\"3500\",\"method\":\"CASH\",\"accountId\":%d}"
			.formatted(family, cash), "pay-1").andExpect(status().isCreated());
		assertThat(db.queryForObject("select count(*) from payment where school_id = ?", Long.class, id)).isPositive();
		assertThat(db.queryForObject("select count(*) from message_outbox where school_id = ?", Long.class, id)).isPositive();

		var tables = db.queryForList("""
				select table_name from information_schema.columns
				where table_schema = 'public' and column_name = 'school_id' and table_name not in ('school', 'platform_audit_log')""",
				String.class);
		long otherRows = 0;
		for (String table : tables) {
			otherRows += db.queryForObject("select count(*) from " + table + " where school_id = ?", Long.class, other.id());
		}

		owner.post("/api/v1/platform/schools/" + id + "/delete",
				"{\"confirmCode\":\"WRONG\",\"reason\":\"Test removal\"}").andExpect(status().isUnprocessableContent());
		owner.post("/api/v1/platform/schools/" + id + "/delete",
				"{\"confirmCode\":\"" + school.code() + "\",\"reason\":\"Test removal\"}").andExpect(status().isNoContent());

		assertThat(db.queryForObject("select count(*) from school where id = ?", Long.class, id)).isZero();
		for (String table : tables) {
			assertThat(db.queryForObject("select count(*) from " + table + " where school_id = ?", Long.class, id))
				.as(table).isZero();
		}
		// The other school keeps every row; the platform history keeps the deletion
		long otherAfter = 0;
		for (String table : tables) {
			otherAfter += db.queryForObject("select count(*) from " + table + " where school_id = ?", Long.class, other.id());
		}
		assertThat(otherAfter).isEqualTo(otherRows);
		new ApiClient(mvc).login(other.code(), "admin", "password-keep1").andExpect(status().isOk());
		assertThat(db.queryForObject("""
				select count(*) from platform_audit_log where action = 'SCHOOL_DELETED' and details->>'code' = ?""",
				Long.class, school.code())).isEqualTo(1);
		owner.get("/api/v1/platform/audit").andExpect(jsonPath("$.items[0].schoolCode").value(school.code()));
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
