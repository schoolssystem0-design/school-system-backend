package com.selectcode.sms.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** New-year rollover (FR 1.3, QA-SET-004) and the built-in role upgrade for new permissions. */
@AutoConfigureMockMvc
class RolloverIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	java.util.List<ApplicationRunner> runners;

	@Test
	void rolloverCopiesChosenItemsIntoTheNewYear() throws Exception {
		SeededSchool school = TestData.school("ROL");
		TestData.admin(school, "admin", "admin-password-1");
		LocalDate start = LocalDate.of(2030, 4, 1);
		long from = TestData.year(school, "2030-2031", start, start.plusYears(1).minusDays(1), true);
		long to = TestData.year(school, "2031-2032", start.plusYears(1), start.plusYears(2).minusDays(1), false);
		long cls = TestData.schoolClass(school, "Class 1", 1);
		long sec = TestData.section(school, cls, "A");
		long sub = TestData.subject(school, "English");
		TestData.classSubject(school, from, cls, sub);
		long teacher = TestData.teacher(school, "Teacher");
		TestDatabase.superuser().update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?)""", school.id(), from, sec, teacher, java.sql.Date.valueOf(start));

		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.get("/api/v1/school/rollover/options").andExpect(jsonPath("$.length()").value(3));
		admin.post("/api/v1/school/years/" + to + "/rollover", """
				{"fromYearId":%d,"items":["CLASS_SUBJECTS","TEACHING_ASSIGNMENTS","WORKING_WEEK"]}""".formatted(from))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.copied.CLASS_SUBJECTS").value(1))
			.andExpect(jsonPath("$.copied.TEACHING_ASSIGNMENTS").value(1));
		// Running it again copies nothing new.
		admin.post("/api/v1/school/years/" + to + "/rollover", """
				{"fromYearId":%d,"items":["CLASS_SUBJECTS"]}""".formatted(from))
			.andExpect(jsonPath("$.copied.CLASS_SUBJECTS").value(0));
		assertThat(TestDatabase.superuser()
			.queryForObject("select count(*) from year_rollover where school_id = ?", Long.class, school.id()))
			.isEqualTo(2);
	}

	@Test
	void builtInRolesReceivePermissionsAddedInLaterPhases() throws Exception {
		SeededSchool school = TestData.school("UPG");
		var db = TestDatabase.superuser();
		long roleId = db.queryForObject("""
				insert into role (school_id, code, name, system_role, template_version) values (?, 'FRONT_OFFICE', 'Front office', true, 1)
				returning id""", Long.class, school.id());
		long accountant = db.queryForObject("""
				insert into role (school_id, code, name, system_role, template_version) values (?, 'ACCOUNTANT', 'Accounts staff', true, 2)
				returning id""", Long.class, school.id());
		// A Phase 1 permission the school removed must stay removed; Phase 2 permissions are added.
		for (ApplicationRunner runner : runners) {
			if (runner.getClass().getSimpleName().startsWith("SystemRoleUpgrader")) {
				runner.run(new DefaultApplicationArguments());
			}
		}
		var permissions = db.queryForList("select permission from role_permission where role_id = ?", String.class, roleId);
		assertThat(permissions).contains("admissions.manage", "students.view").doesNotContain("academics.view");
		// Phase 3: accounts staff get the fee permissions, but not approvals or reversals
		assertThat(db.queryForList("select permission from role_permission where role_id = ?", String.class, accountant))
			.contains("fees.view", "fees.payment.add", "fees.bill.generate")
			.doesNotContain("fees.payment.reverse", "fees.adjust.approve", "academics.view");
		assertThat(db.queryForObject("select template_version from role where id = ?", Integer.class, roleId))
			.isEqualTo(com.selectcode.sms.shared.security.Permissions.CATALOGUE_VERSION);
		ApplicationArguments unused = null;
		assertThat(unused).isNull();
	}
}
