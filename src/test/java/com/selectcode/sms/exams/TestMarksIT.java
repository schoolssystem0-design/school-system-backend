package com.selectcode.sms.exams;

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
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Simple test marks, positions per test and overall, teacher limits, corrections (QA-MRK, D-51). */
@AutoConfigureMockMvc
class TestMarksIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long sec5a;

	long sec5b;

	long maths;

	long english;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	/** Amna, Bilal, Danish, Eman in 5-A. */
	List<Long> students = new ArrayList<>();

	long farah;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("MRK");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		long class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		sec5b = TestData.section(school, class5, "B");
		maths = TestData.subject(school, "Maths");
		english = TestData.subject(school, "English");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		for (String name : List.of("Amna", "Bilal", "Danish", "Eman")) {
			students.add(admit(name, sec5a));
		}
		farah = admit("Farah", sec5b);
	}

	long admit(String name, long sectionId) throws Exception {
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"FEMALE"},"newFamily":{"fatherName":"Father of %s","fatherPhone":"0300%07d"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(name, name, Math.abs(name.hashCode()) % 10_000_000,
				sectionId, today.minusDays(28))).andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	long test(ApiClient client, long sectionId, long subjectId, String name, String max) throws Exception {
		var r = client.post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"%s","testDate":"%s","maxMarks":"%s"}""".formatted(sectionId, subjectId,
				name, today.minusDays(1), max)).andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.test.id")).longValue();
	}

	static String marks(Object... pairs) {
		StringBuilder sb = new StringBuilder("{\"marks\":[");
		for (int i = 0; i < pairs.length; i += 2) {
			String v = String.valueOf(pairs[i + 1]);
			sb.append(i == 0 ? "" : ",").append(v.equals("ABSENT") ? "{\"studentId\":%d,\"absent\":true}".formatted(pairs[i])
					: "{\"studentId\":%d,\"obtained\":\"%s\"}".formatted(pairs[i], v));
		}
		return sb.append("]}").toString();
	}

	void save(long testId, Object... pairs) throws Exception {
		admin.put("/api/v1/test-marks/" + testId + "/marks", marks(pairs)).andExpect(status().isOk());
	}

	static Integer position(ResultActions r, long studentId) throws Exception {
		List<Integer> v = ApiClient.json(r, "$.rows[?(@.studentId==%d)].position".formatted(studentId));
		return v.getFirst();
	}

	@Test
	void marksGivePositionsByTheSchoolsTieRule() throws Exception {
		long t = test(admin, sec5a, maths, "Monthly test", "100");
		save(t, students.get(0), "95", students.get(1), "95", students.get(2), "80", students.get(3), "ABSENT");
		var r = admin.get("/api/v1/test-marks/" + t).andExpect(status().isOk())
			.andExpect(jsonPath("$.rows.length()").value(4))
			.andExpect(jsonPath("$.test.entered").value(4));
		assertThat(position(r, students.get(0))).isEqualTo(1);
		assertThat(position(r, students.get(1))).isEqualTo(1);
		assertThat(position(r, students.get(2))).isEqualTo(3);
		// Absent has no position
		assertThat(position(r, students.get(3))).isNull();
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/results.position_tie_method", "{\"value\":\"DENSE\",\"reason\":\"Test\"}")
			.andExpect(status().isOk());
		r = admin.get("/api/v1/test-marks/" + t);
		assertThat(position(r, students.get(2))).isEqualTo(2);
		// The marks are stored on the student's record, with the position
		admin.get("/api/v1/test-marks/students/" + students.get(2)).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].obtained").value("80"))
			.andExpect(jsonPath("$[0].position").value(2))
			.andExpect(jsonPath("$[0].subjectName").value("Maths"));
	}

	@Test
	void marksAreChecked() throws Exception {
		long t = test(admin, sec5a, maths, "Monthly test", "50");
		String url = "/api/v1/test-marks/" + t + "/marks";
		admin.put(url, marks(students.get(0), "51")).andExpect(jsonPath("$.code").value("MARK_ABOVE_MAX"));
		admin.put(url, marks(students.get(0), "abc")).andExpect(jsonPath("$.code").value("MARK_INVALID"));
		admin.put(url, marks(students.get(0), "1.234")).andExpect(jsonPath("$.code").value("MARK_INVALID"));
		admin.put(url, marks(farah, "20")).andExpect(jsonPath("$.code").value("NOT_IN_SECTION"));
		// The same test twice on one day is refused; a future date too
		admin.post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"monthly TEST","testDate":"%s","maxMarks":"50"}""".formatted(sec5a, maths,
				today.minusDays(1))).andExpect(jsonPath("$.code").value("TEST_EXISTS"));
		admin.post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"Later","testDate":"%s","maxMarks":"50"}""".formatted(sec5a, maths,
				today.plusDays(1))).andExpect(jsonPath("$.code").value("DATE_IN_FUTURE"));
	}

	@Test
	void changingASavedMarkNeedsAReasonAndIsLogged() throws Exception {
		long t = test(admin, sec5a, maths, "Monthly test", "100");
		save(t, students.get(0), "70");
		String url = "/api/v1/test-marks/" + t + "/marks";
		admin.put(url, marks(students.get(0), "75")).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
		admin.put(url, marks(students.get(0), "75").replace("]}", "],\"reason\":\"Added wrongly\"}"))
			.andExpect(status().isOk());
		// Saving the same value again is not a change
		admin.put(url, marks(students.get(0), "75.00")).andExpect(status().isOk());
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("""
				select count(*) from test_mark_change where school_id = ? and old_value = '70' and new_value = '75'
				   and reason = 'Added wrongly'""", Integer.class, school.id())).isEqualTo(1);
		assertThat(db.queryForObject("select count(*) from test_mark_change where school_id = ?", Integer.class,
				school.id())).isEqualTo(1);
		// A test with marks stays; an empty one can be removed
		admin.delete("/api/v1/test-marks/" + t).andExpect(jsonPath("$.code").value("TEST_HAS_MARKS"));
		long empty = test(admin, sec5a, english, "Mistake", "10");
		admin.delete("/api/v1/test-marks/" + empty).andExpect(status().isNoContent());
	}

	@Test
	void overallPositionsUseThePercentageAcrossTests() throws Exception {
		long m = test(admin, sec5a, maths, "Monthly test", "100");
		long e = test(admin, sec5a, english, "Monthly test", "50");
		// Amna 90 + 40 = 130/150 (86.67), Bilal 60 + 50 = 110/150 (73.33), Danish 100 + absent = 100/150 (66.67)
		save(m, students.get(0), "90", students.get(1), "60", students.get(2), "100");
		save(e, students.get(0), "40", students.get(1), "50", students.get(2), "ABSENT");
		admin.get("/api/v1/test-marks/overall?sectionId=%d&from=%s&to=%s".formatted(sec5a, today.minusDays(7), today))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].name").value("Amna"))
			.andExpect(jsonPath("$[0].percent").value("86.67"))
			.andExpect(jsonPath("$[0].position").value(1))
			.andExpect(jsonPath("$[0].testsHeld").value(2))
			.andExpect(jsonPath("$[1].name").value("Bilal"))
			.andExpect(jsonPath("$[1].position").value(2))
			.andExpect(jsonPath("$[2].name").value("Danish"))
			.andExpect(jsonPath("$[2].percent").value("66.67"))
			.andExpect(jsonPath("$[2].position").value(3));
		// A period with no tests is empty
		admin.get("/api/v1/test-marks/overall?sectionId=%d&from=%s&to=%s".formatted(sec5a, today.minusDays(20),
				today.minusDays(10))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void teachersWorkOnlyWithTheirOwnSubjectAndSection() throws Exception {
		long staffId = TestData.teacher(school, "Ms Rabia");
		long userId = TestData.userWithPermissions(school, "rabia", "rabia-password-1", Permissions.EXAMS_VIEW,
				Permissions.EXAMS_MARKS_ENTER);
		var db = TestDatabase.superuser();
		db.update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", staffId, userId);
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?, ?)""", school.id(), yearId, sec5a, maths, staffId, Date.valueOf(today.minusDays(28)));
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		long own = test(teacher, sec5a, maths, "Quiz", "20");
		teacher.put("/api/v1/test-marks/" + own + "/marks", marks(students.get(0), "18")).andExpect(status().isOk());
		String body = """
				{"sectionId":%d,"subjectId":%d,"name":"Quiz","testDate":"%s","maxMarks":"20"}""";
		teacher.post("/api/v1/test-marks", body.formatted(sec5a, english, today.minusDays(1)))
			.andExpect(status().isForbidden());
		teacher.post("/api/v1/test-marks", body.formatted(sec5b, maths, today.minusDays(1)))
			.andExpect(status().isForbidden());
		// Another teacher's test in the same section: visible, not editable. Another section: not visible.
		long english5a = test(admin, sec5a, english, "Quiz", "20");
		teacher.get("/api/v1/test-marks/" + english5a).andExpect(status().isOk())
			.andExpect(jsonPath("$.canEdit").value(false));
		teacher.put("/api/v1/test-marks/" + english5a + "/marks", marks(students.get(0), "18"))
			.andExpect(status().isForbidden());
		long other = test(admin, sec5b, maths, "Quiz", "20");
		teacher.get("/api/v1/test-marks/" + other).andExpect(status().isNotFound());
		teacher.get("/api/v1/test-marks?sectionId=" + sec5b).andExpect(status().isNotFound());
		teacher.get("/api/v1/test-marks/students/" + farah).andExpect(status().isOk());
		save(other, farah, "10");
		teacher.get("/api/v1/test-marks/students/" + farah).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void closedYearsAreReadOnly() throws Exception {
		long t = test(admin, sec5a, maths, "Monthly test", "100");
		save(t, students.get(0), "70");
		TestData.userWithPermissions(school, "office", "office-password-1", Permissions.EXAMS_VIEW,
				Permissions.EXAMS_MARKS_ENTER);
		ApiClient office = new ApiClient(mvc);
		office.login(school.code(), "office", "office-password-1").andExpect(status().isOk());
		TestDatabase.superuser().update("update academic_year set status = 'CLOSED' where id = ?", yearId);
		office.put("/api/v1/test-marks/" + t + "/marks", marks(students.get(1), "60"))
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		office.year(yearId).post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"New","testDate":"%s","maxMarks":"50"}""".formatted(sec5a, english,
				today.minusDays(1))).andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		office.get("/api/v1/test-marks/" + t).andExpect(status().isOk());
	}

	@Test
	void anotherSchoolSeesNothing() throws Exception {
		long t = test(admin, sec5a, maths, "Monthly test", "100");
		save(t, students.get(0), "70");
		SeededSchool other = TestData.school("MRO");
		TestData.admin(other, "admin", "admin-password-1");
		TestData.yearAroundToday(other, true);
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-1").andExpect(status().isOk());
		otherAdmin.get("/api/v1/test-marks/" + t).andExpect(status().isNotFound());
		otherAdmin.put("/api/v1/test-marks/" + t + "/marks", marks(students.get(0), "1")).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/test-marks/students/" + students.get(0)).andExpect(jsonPath("$.length()").value(0));
		otherAdmin.post("/api/v1/test-marks", """
				{"sectionId":%d,"subjectId":%d,"name":"X","testDate":"%s","maxMarks":"5"}""".formatted(sec5a, maths,
				today.minusDays(1))).andExpect(status().isNotFound());
	}
}
