package com.selectcode.sms.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Students: admission, search, moves, withdrawal, promotion, printing, privacy (QA-STU). */
@AutoConfigureMockMvc
class StudentsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long class7;

	long sec7a;

	long sec7b;

	long class8;

	long sec8a;

	LocalDate yearStart;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("STU");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		yearStart = LocalDate.now().minusDays(30);
		class7 = TestData.schoolClass(school, "Class 7", 7);
		sec7a = TestData.section(school, class7, "A");
		sec7b = TestData.section(school, class7, "B");
		class8 = TestData.schoolClass(school, "Class 8", 8);
		sec8a = TestData.section(school, class8, "A");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long admit(String name, String gender, long sectionId, String fatherPhone) throws Exception {
		var result = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"%s","dob":"2014-03-15","phone":"0300-5551112"},
				 "newFamily":{"fatherName":"Father of %s","fatherPhone":"%s"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(name, gender, name, fatherPhone, sectionId, yearStart))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(result, "$.id")).longValue();
	}

	@Test
	void admissionSearchAndClassStrength() throws Exception {
		admin.get("/api/v1/students/suggest-gr").andExpect(jsonPath("$.grNo").value("1"));
		long ali = admit("Ali Raza", "MALE", sec7a, "0300-1000001");
		admit("Fatima Zahra", "FEMALE", sec7a, "0300-1000002");
		admit("Usman Ghani", "MALE", sec7b, "0300-1000003");
		admin.get("/api/v1/students/suggest-gr").andExpect(jsonPath("$.grNo").value("4"));
		admin.get("/api/v1/students/" + ali)
			.andExpect(jsonPath("$.grNo").value("1"))
			.andExpect(jsonPath("$.currentClass.sectionName").value("A"))
			.andExpect(jsonPath("$.details.phone").value("03005551112"));

		// QA-STU-001: by father's name, phone, GR
		admin.get("/api/v1/students?q=father of usman").andExpect(jsonPath("$.items[0].name").value("Usman Ghani"));
		admin.get("/api/v1/students?q=0300 1000002").andExpect(jsonPath("$.items[0].name").value("Fatima Zahra"));
		admin.get("/api/v1/students?q=1").andExpect(jsonPath("$.items[0].name").value("Ali Raza"));
		admin.get("/api/v1/students?sectionId=" + sec7a).andExpect(jsonPath("$.totalItems").value(2));

		admin.get("/api/v1/students/strength")
			.andExpect(jsonPath("$[?(@.sectionId==" + sec7a + ")].total").value(2))
			.andExpect(jsonPath("$[?(@.sectionId==" + sec7a + ")].girls").value(1))
			.andExpect(jsonPath("$[?(@.sectionId==" + sec7b + ")].boys").value(1));
	}

	@Test
	void moveKeepsHistoryWithTheOldSection() throws Exception {
		// QA-STU-003 / FR 5.4
		long id = admit("Hassan", "MALE", sec7a, "0300-2000001");
		LocalDate moveDate = LocalDate.now().minusDays(5);
		admin.post("/api/v1/students/" + id + "/move", """
				{"toSectionId":%d,"effectiveDate":"%s","reason":"Balance class sizes"}""".formatted(sec7b, moveDate))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentClass.sectionName").value("B"));
		admin.get("/api/v1/students/" + id + "/enrolments")
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].sectionName").value("B"))
			.andExpect(jsonPath("$[0].kind").value("MOVE"))
			.andExpect(jsonPath("$[1].sectionName").value("A"))
			.andExpect(jsonPath("$[1].effectiveTo").value(moveDate.minusDays(1).toString()));
		admin.post("/api/v1/students/" + id + "/move", "{\"toSectionId\":%d}".formatted(sec7b))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SAME_SECTION"));
		// The database refuses overlapping class membership.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.superuser().update("""
				insert into enrolment (school_id, student_id, academic_year_id, class_id, section_id, effective_from, kind)
				values (?, ?, ?, ?, ?, ?, 'MOVE')""", school.id(), id, yearId, class7, sec7a,
				java.sql.Date.valueOf(LocalDate.now()))).hasMessageContaining("enrolment_no_overlap");
	}

	@Test
	void withdrawalAndCertificates() throws Exception {
		// QA-STU-005 / 006 / 007
		long id = admit("Zara Khan", "FEMALE", sec7a, "0300-3000001");
		admin.post("/api/v1/students/" + id + "/certificates/LEAVING", "{}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("NOT_LEFT"));
		admin.post("/api/v1/students/" + id + "/withdraw", """
				{"leavingDate":"%s","reason":"Family moved to Karachi"}""".formatted(LocalDate.now()))
			.andExpect(jsonPath("$.status").value("LEFT"));
		admin.get("/api/v1/students?status=LEFT").andExpect(jsonPath("$.items[*].name").value(hasItem("Zara Khan")));
		admin.get("/api/v1/students").andExpect(jsonPath("$.items[*].name").value(not(hasItem("Zara Khan"))));
		admin.post("/api/v1/students/" + id + "/move", "{\"toSectionId\":%d}".formatted(sec7b))
			.andExpect(status().is4xxClientError());

		byte[] pdf = admin.post("/api/v1/students/" + id + "/certificates/LEAVING", "{\"conduct\":\"excellent\"}")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		admin.post("/api/v1/students/" + id + "/certificates/CHARACTER", "{}").andExpect(status().isOk());
		admin.get("/api/v1/students/" + id + "/certificates")
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[*].serialNo").value(hasItem("LC-00001")))
			.andExpect(jsonPath("$[*].serialNo").value(hasItem("CC-00001")));
		assertThat(TestDatabase.superuser()
			.queryForObject("select reason from withdrawal where student_id = ?", String.class, id))
			.isEqualTo("Family moved to Karachi");
	}

	@Test
	void idCardsBioDataAndBulkSeatNumbers() throws Exception {
		long a = admit("Student One", "MALE", sec7a, "0300-4000001");
		long b = admit("Student Two", "FEMALE", sec7a, "0300-4000002");
		byte[] cards = admin.post("/api/v1/students/id-cards", "{\"sectionId\":%d}".formatted(sec7a))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(new String(cards, 0, 5)).isEqualTo("%PDF-");
		admin.get("/api/v1/students/" + a + "/bio-data").andExpect(status().isOk());

		// QA-STU-002
		admin.put("/api/v1/students/bulk", """
				{"field":"SEAT_NO","values":[{"studentId":%d,"value":"10101"},{"studentId":%d,"value":"10102"}]}"""
			.formatted(a, b)).andExpect(jsonPath("$.updated").value(2));
		admin.get("/api/v1/students/" + b).andExpect(jsonPath("$.details.seatNo").value("10102"));
	}

	@Test
	void promotionRepeatAndLeave() throws Exception {
		// QA-STU-004 / FR 5.5
		long p = admit("Promoted", "MALE", sec7a, "0300-5000001");
		long r = admit("Repeats", "MALE", sec7a, "0300-5000002");
		long l = admit("Leaves", "FEMALE", sec7b, "0300-5000003");
		LocalDate nextStart = yearStart.plusDays(331);
		long next = TestData.year(school, (nextStart.getYear()) + "-" + (nextStart.getYear() + 1) + "x".substring(1),
				nextStart, nextStart.plusDays(330), false);
		admin.get("/api/v1/students/promotion/preview?fromYearId=%d&toYearId=%d&classId=%d".formatted(yearId, next, class7))
			.andExpect(jsonPath("$.nextClassName").value("Class 8"))
			.andExpect(jsonPath("$.students.length()").value(3));
		admin.post("/api/v1/students/promotion", """
				{"fromYearId":%d,"toYearId":%d,"items":[
				  {"studentId":%d,"decision":"PROMOTE","toSectionId":%d},
				  {"studentId":%d,"decision":"REPEAT","toSectionId":%d},
				  {"studentId":%d,"decision":"LEAVE","reason":"Moving abroad"}]}"""
			.formatted(yearId, next, p, sec8a, r, sec7a, l))
			.andExpect(jsonPath("$.promoted").value(1))
			.andExpect(jsonPath("$.repeating").value(1))
			.andExpect(jsonPath("$.left").value(1));
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("select class_id from enrolment where student_id = ? and academic_year_id = ?",
				Long.class, p, next)).isEqualTo(class8);
		assertThat(db.queryForObject("select kind from enrolment where student_id = ? and academic_year_id = ?",
				String.class, r, next)).isEqualTo("REPEAT");
		assertThat(db.queryForObject("select status from student where id = ?", String.class, l)).isEqualTo("LEFT");
		// Last year's class is unchanged.
		assertThat(db.queryForObject("select class_id from enrolment where student_id = ? and academic_year_id = ?",
				Long.class, p, yearId)).isEqualTo(class7);
		// Promoting twice is refused.
		admin.post("/api/v1/students/promotion", """
				{"fromYearId":%d,"toYearId":%d,"items":[{"studentId":%d,"decision":"PROMOTE","toSectionId":%d}]}"""
			.formatted(yearId, next, p, sec8a)).andExpect(status().isConflict());
	}

	@Test
	void privateDetailsAreHiddenAndTeachersSeeOnlyTheirSections() throws Exception {
		// QA-SEC-013 / FR 5.11 and QA-SEC-011 / FR 2.5
		long inA = admit("In Seven A", "MALE", sec7a, "0300-6000001");
		long inB = admit("In Seven B", "MALE", sec7b, "0300-6000002");
		long teacherStaff = TestData.teacher(school, "Mr. Ahmed");
		long userId = TestData.userWithPermissions(school, "ahmed", "teacher-pass-1", Permissions.STUDENTS_VIEW,
				Permissions.ACADEMICS_VIEW);
		var db = TestDatabase.superuser();
		db.update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", teacherStaff, userId);
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?)""", school.id(), yearId, sec7a, teacherStaff, java.sql.Date.valueOf(yearStart));

		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "ahmed", "teacher-pass-1").andExpect(status().isOk());
		teacher.get("/api/v1/students")
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].name").value("In Seven A"))
			.andExpect(jsonPath("$.items[0].fatherPhone").doesNotExist());
		teacher.get("/api/v1/students/" + inA)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.privateHidden").value(true))
			.andExpect(jsonPath("$.details.phone").doesNotExist())
			.andExpect(jsonPath("$.family.fatherPhone").doesNotExist());
		teacher.get("/api/v1/students/" + inB).andExpect(status().isNotFound());
		teacher.get("/api/v1/students/" + inA + "/documents").andExpect(jsonPath("$.length()").value(0));
		teacher.patch("/api/v1/students/" + inA, "{\"student\":{\"name\":\"x\",\"gender\":\"MALE\"}}")
			.andExpect(status().isForbidden());
	}
}
