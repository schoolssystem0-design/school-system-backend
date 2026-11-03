package com.selectcode.sms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Staff records, logins, salary, leaving, teaching assignments and timetable (QA-STF). */
@AutoConfigureMockMvc
class StaffIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long classId;

	long secA;

	long secB;

	long maths;

	long teacherRoleId;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("STF");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		classId = TestData.schoolClass(school, "Class 8", 8);
		secA = TestData.section(school, classId, "A");
		secB = TestData.section(school, classId, "B");
		maths = TestData.subject(school, "Mathematics");
		TestData.classSubject(school, yearId, classId, maths);
		teacherRoleId = TestDatabase.superuser().queryForObject("""
				insert into role (school_id, code, name, system_role) values (?, 'TEACHER', 'Teacher', true) returning id""",
				Long.class, school.id());
		TestDatabase.superuser().update("""
				insert into role_permission (school_id, role_id, permission) values (?, ?, ?), (?, ?, ?)""", school.id(),
				teacherRoleId, Permissions.STUDENTS_VIEW, school.id(), teacherRoleId, Permissions.ACADEMICS_VIEW);
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long createTeacher(String name) throws Exception {
		var result = admin.post("/api/v1/staff", """
				{"category":"TEACHER","name":"%s","designation":"Mathematics teacher","joiningDate":"%s",
				 "cnic":"35202-7654321-9","phone":"0333-1234567"}""".formatted(name, LocalDate.now().minusYears(1)))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(result, "$.id")).longValue();
	}

	@Test
	void teacherRecordLoginAndSalary() throws Exception {
		long id = createTeacher("Ahmed Raza");
		admin.get("/api/v1/staff/" + id)
			.andExpect(jsonPath("$.employeeNo").value("EMP-0001"))
			.andExpect(jsonPath("$.details.phone").value("03331234567"));

		// FR 6.2: login with the teacher role; teachers default to "assigned classes".
		admin.post("/api/v1/staff/" + id + "/login", "{\"username\":\"ahmed\",\"roleIds\":[%d]}".formatted(teacherRoleId))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
		admin.stepUp(outbox);
		var login = admin.post("/api/v1/staff/" + id + "/login", "{\"username\":\"ahmed\",\"roleIds\":[%d]}".formatted(teacherRoleId))
			.andExpect(status().isCreated());
		assertThat(TestDatabase.superuser()
			.queryForObject("select data_scope from app_user where username = 'ahmed' and school_id = ?", String.class,
					school.id())).isEqualTo("ASSIGNED_CLASSES");
		admin.post("/api/v1/staff/" + id + "/login", "{\"username\":\"ahmed2\",\"roleIds\":[%d]}".formatted(teacherRoleId))
			.andExpect(status().isConflict());
		admin.get("/api/v1/staff/" + id).andExpect(jsonPath("$.login.username").value("ahmed"));

		// FR 6.8: salary history; an increment is a new row.
		admin.post("/api/v1/staff/" + id + "/salary", """
				{"effectiveFrom":"%s","basicSalary":"45000","reason":"Starting salary"}""".formatted(LocalDate.now().minusYears(1)))
			.andExpect(status().isCreated());
		admin.post("/api/v1/staff/" + id + "/salary", """
				{"effectiveFrom":"%s","basicSalary":"49500.00","reason":"Annual increment"}""".formatted(LocalDate.now()))
			.andExpect(status().isCreated());
		admin.get("/api/v1/staff/" + id + "/salary")
			.andExpect(jsonPath("$[0].basicSalary").value("49500.00"))
			.andExpect(jsonPath("$[1].basicSalary").value("45000.00"));

		// Without salary permission the history is not visible.
		TestData.userWithPermissions(school, "office", "office-pass-12", Permissions.STAFF_VIEW);
		ApiClient office = new ApiClient(mvc);
		office.login(school.code(), "office", "office-pass-12").andExpect(status().isOk());
		office.get("/api/v1/staff/" + id).andExpect(status().isOk());
		office.get("/api/v1/staff/" + id + "/salary").andExpect(status().isForbidden());
		assertThat(ApiClient.<String>json(login, "$.temporaryPassword")).hasSize(12);
	}

	@Test
	void assignmentsHandoverAndTimetable() throws Exception {
		long ahmed = createTeacher("Ahmed");
		long sana = createTeacher("Sana");
		String base = "/api/v1/academics/years/" + yearId;
		// A subject must be taught in the class before assigning it.
		long english = TestData.subject(school, "English");
		admin.post(base + "/assignments", "{\"sectionId\":%d,\"subjectId\":%d,\"staffId\":%d}".formatted(secA, english, ahmed))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SUBJECT_NOT_IN_CLASS"));

		// FR 6.3 example: Maths in 8-A and 8-B and class teacher of 8-A.
		String since = LocalDate.now().minusDays(20).toString();
		admin.post(base + "/assignments", "{\"sectionId\":%d,\"staffId\":%d,\"effectiveFrom\":\"%s\"}".formatted(secA, ahmed, since))
			.andExpect(status().isCreated());
		admin.post(base + "/assignments", "{\"sectionId\":%d,\"subjectId\":%d,\"staffId\":%d,\"effectiveFrom\":\"%s\"}"
			.formatted(secA, maths, ahmed, since)).andExpect(status().isCreated());
		admin.post(base + "/assignments", "{\"sectionId\":%d,\"subjectId\":%d,\"staffId\":%d,\"effectiveFrom\":\"%s\"}"
			.formatted(secB, maths, ahmed, since)).andExpect(status().isCreated());
		admin.get(base + "/assignments?staffId=" + ahmed).andExpect(jsonPath("$.length()").value(3));

		// Timetable: Ahmed cannot be in two sections in the same period.
		admin.put(base + "/sections/" + secA + "/timetable",
				"[{\"weekday\":1,\"periodNo\":1,\"subjectId\":%d,\"staffId\":%d}]".formatted(maths, ahmed))
			.andExpect(status().isOk());
		admin.put(base + "/sections/" + secB + "/timetable",
				"[{\"weekday\":1,\"periodNo\":1,\"subjectId\":%d,\"staffId\":%d}]".formatted(maths, ahmed))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TEACHER_DOUBLE_BOOKED"));

		// FR 6.6: handover to Sana from today; past assignments stay with Ahmed.
		admin.post(base + "/assignments/handover", "{\"fromStaffId\":%d,\"toStaffId\":%d,\"effectiveFrom\":\"%s\"}"
			.formatted(ahmed, sana, LocalDate.now())).andExpect(jsonPath("$.moved").value(3));
		admin.get(base + "/assignments?staffId=" + sana).andExpect(jsonPath("$.length()").value(3));
		admin.get(base + "/assignments?staffId=" + ahmed + "&currentOnly=false")
			.andExpect(jsonPath("$[0].effectiveTo").value(LocalDate.now().minusDays(1).toString()));
		admin.get(base + "/timetable?staffId=" + sana).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void leavingDeactivatesLoginAndEndsAssignments() throws Exception {
		// QA-STF-003 / FR 6.7
		long id = createTeacher("Leaving Teacher");
		admin.stepUp(outbox);
		admin.post("/api/v1/staff/" + id + "/login", "{\"username\":\"leaver\",\"roleIds\":[%d]}".formatted(teacherRoleId))
			.andExpect(status().isCreated());
		String base = "/api/v1/academics/years/" + yearId;
		admin.post(base + "/assignments", "{\"sectionId\":%d,\"staffId\":%d,\"effectiveFrom\":\"%s\"}"
			.formatted(secA, id, LocalDate.now().minusDays(20))).andExpect(status().isCreated());

		admin.post("/api/v1/staff/" + id + "/leave", "{\"leavingDate\":\"%s\",\"reason\":\"Resigned\"}".formatted(LocalDate.now()))
			.andExpect(jsonPath("$.status").value("LEFT"))
			.andExpect(jsonPath("$.login.status").value("INACTIVE"));
		admin.get(base + "/assignments?staffId=" + id).andExpect(jsonPath("$.length()").value(0));
		admin.get(base + "/assignments?staffId=" + id + "&currentOnly=false")
			.andExpect(jsonPath("$[0].effectiveTo").value(LocalDate.now().toString()));
		admin.get("/api/v1/staff?status=ACTIVE").andExpect(jsonPath("$.totalItems").value(0));

		byte[] pdf = admin.post("/api/v1/staff/" + id + "/certificates/experience", "{}")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
	}
}
