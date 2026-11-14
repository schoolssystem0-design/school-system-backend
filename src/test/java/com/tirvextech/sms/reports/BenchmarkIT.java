package com.tirvextech.sms.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.shared.security.Permissions;
import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Comparing a school with similar schools: averages only, at least 5 schools, opt-out respected. */
@AutoConfigureMockMvc
class BenchmarkIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Karachi")).minusDays(1);

	/** A school of 250 students (band 200–499) and 10 teachers; {@code present} of them were present yesterday. */
	TestData.SeededSchool school(String prefix, int present) {
		var school = TestData.school(prefix);
		var db = TestDatabase.superuser();
		long year = TestData.yearAroundToday(school, true);
		long section = TestData.section(school, TestData.schoolClass(school, "Class 1", 1), "A");
		long family = db.queryForObject("insert into family (school_id, family_no, father_name) values (?, 'F-1', 'Father') returning id",
				Long.class, school.id());
		db.update("""
				insert into student (school_id, gr_no, name, gender, admission_date, family_id)
				select ?, lpad(g::text, 3, '0'), 'Student ' || g, case when g % 2 = 0 then 'MALE' else 'FEMALE' end, current_date - 60, ?
				from generate_series(1, 250) g""", school.id(), family);
		db.update("""
				insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
				select s.school_id, ?, ?, ?, s.id, case when row_number() over (order by s.id) <= ? then 'P' else 'A' end
				from student s where s.school_id = ?""", year, Date.valueOf(yesterday), section, present, school.id());
		for (int i = 0; i < 10; i++) {
			TestData.teacher(school, "Teacher " + i);
		}
		return school;
	}

	@Test
	void aSchoolSeesTheAverageOfSimilarSchoolsButNeverTheirFigures() throws Exception {
		var mine = school("BMA", 150); // 60%
		List<TestData.SeededSchool> peers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			peers.add(school("BMP" + i, 225)); // 90%
		}
		TestData.admin(mine, "admin", "admin-password-1");
		ApiClient admin = new ApiClient(mvc);
		admin.login(mine.code(), "admin", "admin-password-1").andExpect(status().isOk());

		var r = admin.get("/api/v1/reports/benchmark").andExpect(status().isOk())
			.andExpect(jsonPath("$.sharing").value(true))
			.andExpect(jsonPath("$.studentsHere").value(250))
			.andExpect(jsonPath("$.comparedWith").value("200–499 students"))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].value").value(org.hamcrest.Matchers.hasItem("60.0")))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].average").value(org.hamcrest.Matchers.hasItem("90.0")))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].schools").value(org.hamcrest.Matchers.hasItem(5)))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].verdict").value(org.hamcrest.Matchers.hasItem("BEHIND")))
			.andExpect(jsonPath("$.measures[?(@.key=='ratio')].value").value(org.hamcrest.Matchers.hasItem("25.0")))
			.andExpect(jsonPath("$.measures[?(@.key=='ratio')].verdict").value(org.hamcrest.Matchers.hasItem("SIMILAR")))
			.andExpect(jsonPath("$.measures[?(@.key=='fees')].verdict").value(org.hamcrest.Matchers.hasItem("NO_DATA")));
		List<String> hint = ApiClient.json(r, "$.measures[?(@.key=='attendance')].hint");
		assertThat(hint.getFirst()).contains("absence SMS");
		// Nothing in the answer names or identifies another school
		String body = r.andReturn().getResponse().getContentAsString();
		for (var p : peers) {
			assertThat(body).doesNotContain(p.code()).doesNotContain("\"" + p.id() + "\"");
		}

		// A school that turned sharing off is left out, and sees no averages itself
		var db = TestDatabase.superuser();
		var quiet = peers.getFirst();
		db.update("""
				insert into setting_value (school_id, setting_key, value, reason, changed_by_type)
				values (?, 'general.benchmark_share', 'false'::jsonb, 'Private', 'SYSTEM')""", quiet.id());
		TestData.admin(quiet, "admin", "admin-password-1");
		ApiClient quietAdmin = new ApiClient(mvc);
		quietAdmin.login(quiet.code(), "admin", "admin-password-1").andExpect(status().isOk());
		quietAdmin.get("/api/v1/reports/benchmark")
			.andExpect(jsonPath("$.sharing").value(false))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].value").value(org.hamcrest.Matchers.hasItem("90.0")))
			.andExpect(jsonPath("$.measures[?(@.key=='attendance')].average").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
		// The other school now has only 4 similar schools: never an average of fewer than 5
		var again = admin.get("/api/v1/reports/benchmark");
		List<Integer> schools = ApiClient.json(again, "$.measures[?(@.key=='attendance')].schools");
		assertThat(schools.getFirst() == null || schools.getFirst() >= 5).isTrue();

		// Teachers limited to their classes, and staff without reports, get nothing
		long teacherUser = TestData.userWithPermissions(mine, "rabia", "rabia-password-1", Permissions.REPORTS_VIEW);
		db.update("update app_user set data_scope = 'ASSIGNED_CLASSES' where id = ?", teacherUser);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(mine.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		teacher.get("/api/v1/reports/benchmark").andExpect(status().isForbidden());
		TestData.userWithPermissions(mine, "clerk", "clerk-password-1", Permissions.STUDENTS_VIEW);
		ApiClient clerk = new ApiClient(mvc);
		clerk.login(mine.code(), "clerk", "clerk-password-1").andExpect(status().isOk());
		clerk.get("/api/v1/reports/benchmark").andExpect(status().isForbidden());
	}
}
