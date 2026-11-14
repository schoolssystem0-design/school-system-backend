package com.tirvextech.sms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The owner's business dashboard: paying schools, trials by interest, school health with reasons, growth. */
@AutoConfigureMockMvc
class OwnerDashboardIT extends IntegrationTest {

	static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	@Autowired
	MockMvc mvc;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	/** A school in use: two people signed in this week and a register taken. */
	TestData.SeededSchool busy(String prefix) throws Exception {
		var school = TestData.school(prefix);
		TestData.admin(school, "admin", "admin-password-1");
		TestData.admin(school, "office", "office-password-1");
		new ApiClient(mvc).login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		ApiClient office = new ApiClient(mvc);
		office.login(school.code(), "office", "office-password-1").andExpect(status().isOk());
		long year = TestData.yearAroundToday(school, true);
		long section = TestData.section(school, TestData.schoolClass(school, "Class 1", 1), "A");
		var r = office.post("/api/v1/students", """
				{"student":{"name":"Busy Child","gender":"MALE"},"newFamily":{"fatherName":"Busy Father"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(section, today.minusDays(10))).andExpect(status().isCreated());
		long student = ((Number) ApiClient.json(r, "$.id")).longValue();
		TestDatabase.superuser().update("""
				insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
				values (?, ?, ?, ?, ?, 'P')""", school.id(), year, Date.valueOf(today.minusDays(1)), section, student);
		return school;
	}

	@Test
	void theOwnerSeesPayingSchoolsTrialsAndWhoNeedsACall() throws Exception {
		String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		TestData.superAdmin(email, "owner-password-1", SECRET);
		ApiClient owner = new ApiClient(mvc);
		owner.platformLogin(email, "owner-password-1", SECRET).andExpect(status().isOk());
		var db = TestDatabase.superuser();

		var paying = busy("DPAY");
		owner.put("/api/v1/platform/schools/" + paying.id() + "/subscription",
				"{\"cycle\":\"MONTHLY\",\"amount\":\"5000\",\"startOn\":\"" + today.plusDays(20) + "\"}").andExpect(status().isOk());
		var trial = busy("DTRY");
		db.update("update school set trial_ends_at = now() + interval '3 days' where id = ?", trial.id());
		db.update("insert into trial_signup (ip, email, school_code, created_school_id) values (?, ?, ?, ?)",
				"10.9.8." + (trial.id() % 250), "t" + trial.id() + "@x.test", trial.code(), trial.id());
		var idle = TestData.school("DIDL");
		db.update("update school set created_at = now() - interval '10 days' where id = ?", idle.id());

		var r = owner.get("/api/v1/platform/dashboard").andExpect(status().isOk());
		assertThat(ApiClient.<Integer>json(r, "$.headline.paying")).isGreaterThanOrEqualTo(1);
		assertThat(ApiClient.<Integer>json(r, "$.headline.trialsEndingThisWeek")).isGreaterThanOrEqualTo(1);
		assertThat(ApiClient.<Integer>json(r, "$.conversion.trialsAllTime")).isGreaterThanOrEqualTo(1);
		assertThat(ApiClient.<List<Object>>json(r, "$.growth")).hasSize(12);

		List<String> payingHealth = ApiClient.json(r, "$.schools[?(@.schoolId==" + paying.id() + ")].health");
		List<String> payingPlan = ApiClient.json(r, "$.schools[?(@.schoolId==" + paying.id() + ")].plan");
		List<Number> payingUsers = ApiClient.json(r, "$.schools[?(@.schoolId==" + paying.id() + ")].activeUsers7d");
		assertThat(payingHealth).containsExactly("GREEN");
		assertThat(payingPlan).containsExactly("Monthly");
		assertThat(payingUsers.getFirst().intValue()).isEqualTo(2);

		List<String> trialInterest = ApiClient.json(r, "$.trials[?(@.schoolId==" + trial.id() + ")].interest");
		List<Number> daysLeft = ApiClient.json(r, "$.trials[?(@.schoolId==" + trial.id() + ")].daysLeft");
		assertThat(trialInterest).containsExactly("HOT");
		assertThat(daysLeft.getFirst().intValue()).isBetween(2, 3);

		List<String> idleHealth = ApiClient.json(r, "$.schools[?(@.schoolId==" + idle.id() + ")].health");
		List<List<String>> idleReasons = ApiClient.json(r, "$.schools[?(@.schoolId==" + idle.id() + ")].reasons");
		assertThat(idleHealth).containsExactly("RED");
		assertThat(idleReasons.getFirst()).contains("Nobody has signed in yet");

		// Only the owner
		ApiClient admin = new ApiClient(mvc);
		admin.login(paying.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.get("/api/v1/platform/dashboard").andExpect(status().isForbidden());
	}
}
