package com.selectcode.sms.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.exams.app.ResultService;
import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.math.BigDecimal;
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

/** Exams, marks entry, grading, the publishing workflow, report cards and cumulative results (QA-EXM). */
@AutoConfigureMockMvc
class ExamsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long class5;

	long sec5a;

	long sec5b;

	long maths;

	long english;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	/** Amna, Bilal, Danish, Eman in 5-A; Farah in 5-B. */
	List<Long> students = new ArrayList<>();

	long farah;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("EXM");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		sec5b = TestData.section(school, class5, "B");
		maths = TestData.subject(school, "Maths");
		english = TestData.subject(school, "English");
		TestData.classSubject(school, yearId, class5, maths);
		TestData.classSubject(school, yearId, class5, english);
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

	/** Creates an exam for class 5 (papers copied from the class subjects) and returns the exam class id. */
	long examClass(String name, boolean visible, String weight) throws Exception {
		var r = admin.post("/api/v1/exams", """
				{"name":"%s","startDate":"%s","endDate":"%s","visible":%s,"weight":"%s"}""".formatted(name,
				today.minusDays(6), today.minusDays(1), visible, weight)).andExpect(status().isCreated());
		long examId = ((Number) ApiClient.json(r, "$.id")).longValue();
		var c = admin.post("/api/v1/exams/" + examId + "/classes", "{\"classIds\":[" + class5 + "]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.classes[0].papers").value(2));
		return ((Number) ApiClient.json(c, "$.classes[0].id")).longValue();
	}

	/** Paper ids of an exam class: [maths, english]. */
	long[] papers(long examClassId) throws Exception {
		var r = admin.get("/api/v1/exams/classes/" + examClassId).andExpect(status().isOk());
		List<Number> ids = ApiClient.json(r, "$.papers[*].id");
		List<Number> subjects = ApiClient.json(r, "$.papers[*].subjectId");
		long[] out = new long[2];
		for (int i = 0; i < ids.size(); i++) {
			out[subjects.get(i).longValue() == maths ? 0 : 1] = ids.get(i).longValue();
		}
		return out;
	}

	/** Pairs of studentId, value; a value of "ABSENT"/"EXEMPT" is a flag, otherwise marks. */
	static String marks(Object... pairs) {
		StringBuilder sb = new StringBuilder("{\"marks\":[");
		for (int i = 0; i < pairs.length; i += 2) {
			String v = String.valueOf(pairs[i + 1]);
			String field = v.equals("ABSENT") || v.equals("EXEMPT") ? "flag"
					: v.matches("[0-9.]*") ? "obtained" : "grade";
			sb.append(i == 0 ? "" : ",").append("{\"studentId\":%d,\"%s\":\"%s\"}".formatted(pairs[i], field, v));
		}
		return sb.append("]}").toString();
	}

	void enter(long examClassId, long sectionId, long paper, Object... pairs) throws Exception {
		admin.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(examClassId, sectionId, paper),
				marks(pairs))
			.andExpect(status().isOk());
	}

	/** Enters the same two papers for everyone, Farah (5-B) gets 50 + 50. */
	void enterAll(long ec, long[] p, Object... mathsThenEnglish) throws Exception {
		List<Object> m = new ArrayList<>();
		List<Object> e = new ArrayList<>();
		for (int i = 0; i < students.size(); i++) {
			m.add(students.get(i));
			m.add(mathsThenEnglish[i * 2]);
			e.add(students.get(i));
			e.add(mathsThenEnglish[i * 2 + 1]);
		}
		enter(ec, sec5a, p[0], m.toArray());
		enter(ec, sec5a, p[1], e.toArray());
		enter(ec, sec5b, p[0], farah, "50");
		enter(ec, sec5b, p[1], farah, "50");
	}

	void setting(String key, String value) throws Exception {
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/" + key, "{\"value\":\"%s\",\"reason\":\"Test\"}".formatted(value))
			.andExpect(status().isOk());
	}

	ApiClient teacherOf(long sectionId, long subjectId) throws Exception {
		long staffId = TestData.teacher(school, "Ms Rabia");
		long userId = TestData.userWithPermissions(school, "rabia", "rabia-password-1", Permissions.EXAMS_VIEW,
				Permissions.EXAMS_MARKS_ENTER);
		var db = TestDatabase.superuser();
		db.update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", staffId, userId);
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?, ?)""", school.id(), yearId, sectionId, subjectId, staffId,
				Date.valueOf(today.minusDays(28)));
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		return teacher;
	}

	// ------------------------------------------------------------------ tests

	@Test
	void hiddenExamsAndTeachersOwnSubjectsOnly() throws Exception {
		long hidden = examClass("Mid term", false, "1");
		long visible = examClass("Monthly test", true, "1");
		long[] p = papers(visible);
		ApiClient teacher = teacherOf(sec5a, maths);
		// QA-EXM-001: teachers do not see a hidden exam
		teacher.get("/api/v1/exams").andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Monthly test"));
		teacher.get("/api/v1/exams/classes/" + hidden).andExpect(status().isNotFound());
		admin.get("/api/v1/exams").andExpect(jsonPath("$.length()").value(2));
		// QA-EXM-003: own subject and section only
		teacher.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(visible, sec5a, p[0]),
				marks(students.get(0), "70"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)].obtained".formatted(students.get(0))).value("70"));
		teacher.get("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(visible, sec5a, p[1]))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_YOUR_SUBJECT"));
		teacher.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(visible, sec5b, p[0]),
				marks(farah, "70"))
			.andExpect(status().isForbidden());
		// The teacher's results view has 5-A only; setting up exams is not theirs
		teacher.get("/api/v1/exams/classes/" + visible + "/results")
			.andExpect(jsonPath("$.students.length()").value(4));
		teacher.post("/api/v1/exams", "{\"name\":\"Final\"}").andExpect(status().isForbidden());
		teacher.post("/api/v1/exams/classes/" + visible + "/review").andExpect(status().isForbidden());
	}

	@Test
	void marksAreValidatedAgainstThePaper() throws Exception {
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		String url = "/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5a, p[0]);
		// QA-EXM-002
		admin.put(url, marks(students.get(0), "101")).andExpect(jsonPath("$.code").value("MARK_ABOVE_MAX"));
		admin.put(url, marks(students.get(0), "12.345")).andExpect(jsonPath("$.code").value("MARK_INVALID"));
		admin.put(url, marks(farah, "50")).andExpect(jsonPath("$.code").value("NOT_IN_SECTION"));
		admin.put(url, marks(students.get(0), "100", students.get(1), "ABSENT")).andExpect(status().isOk());
		// The maximum cannot go below marks already entered
		admin.put("/api/v1/exams/classes/" + ec + "/papers", """
				{"papers":[{"subjectId":%d,"maxMarks":"50"},{"subjectId":%d,"maxMarks":"100"}]}""".formatted(maths,
				english))
			.andExpect(status().is4xxClientError());
		// Clearing a mark makes it missing again
		admin.put(url, "{\"marks\":[{\"studentId\":%d}]}".formatted(students.get(1))).andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)].flag".formatted(students.get(1))).value(
					org.hamcrest.Matchers.contains((Object) null)));
	}

	@Test
	void gradeBoundariesAreInclusive() throws Exception {
		// QA-EXM-005: 94.99 → A+, 95.00 → A++, 100 → A++, 0 → F
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "100", "89.98", "100", "90", "100", "100", "0", "0");
		var r = admin.get("/api/v1/exams/classes/" + ec + "/results").andExpect(status().isOk())
			.andExpect(jsonPath("$.live").value(true));
		assertThat(gradeOf(r, students.get(0))).isEqualTo("A+");
		assertThat(percentOf(r, students.get(0))).isEqualTo("94.99");
		assertThat(gradeOf(r, students.get(1))).isEqualTo("A++");
		assertThat(percentOf(r, students.get(1))).isEqualTo("95.00");
		assertThat(gradeOf(r, students.get(2))).isEqualTo("A++");
		assertThat(gradeOf(r, students.get(3))).isEqualTo("F");
		r.andExpect(jsonPath("$.students[?(@.studentId==%d)].passed".formatted(students.get(3))).value(
				org.hamcrest.Matchers.contains(false)));
		// Pure rules
		assertThat(ResultService.percent(new BigDecimal("2"), new BigDecimal("3"))).isEqualByComparingTo("66.67");
	}

	@Test
	void tiesFollowTheSchoolsRule() throws Exception {
		// QA-EXM-007
		setting("results.show_class_positions", "true");
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "95", "95", "95", "95", "90", "90", "40", "40");
		var r = admin.get("/api/v1/exams/classes/" + ec + "/results");
		assertThat(positionOf(r, students.get(0))).isEqualTo(1);
		assertThat(positionOf(r, students.get(1))).isEqualTo(1);
		assertThat(positionOf(r, students.get(2))).isEqualTo(3);
		// Positions are over the whole class: Farah (50%) is 4th, Eman (40%) 5th
		assertThat(positionOf(r, farah)).isEqualTo(4);
		setting("results.position_tie_method", "DENSE");
		r = admin.get("/api/v1/exams/classes/" + ec + "/results");
		assertThat(positionOf(r, students.get(2))).isEqualTo(2);
		assertThat(positionOf(r, students.get(3))).isEqualTo(4);
		assertThat(ResultService.rank(java.util.Arrays.asList(new BigDecimal("80"), null, new BigDecimal("80")),
				"STANDARD")).containsExactly(1, null, 1);
	}

	@Test
	void absentIsAnEntryButMissingBlocksPublishing() throws Exception {
		// QA-EXM-004
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "80", "80", "70", "70", "60", "60", "ABSENT", "90");
		admin.get("/api/v1/exams/classes/" + ec + "/missing").andExpect(jsonPath("$.length()").value(0));
		var r = admin.get("/api/v1/exams/classes/" + ec + "/results");
		r.andExpect(jsonPath("$.students[?(@.studentId==%d)].passed".formatted(students.get(3))).value(
				org.hamcrest.Matchers.contains(false)));
		// Absent counts as 0 of 100 → 45.00
		assertThat(percentOf(r, students.get(3))).isEqualTo("45.00");
		// Clear one mark: now missing, and publishing is refused
		admin.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5b, p[1]),
				"{\"marks\":[{\"studentId\":%d}]}".formatted(farah))
			.andExpect(status().isOk());
		admin.get("/api/v1/exams/classes/" + ec + "/missing").andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].sectionId").value(sec5b));
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(jsonPath("$.code").value("MARKS_MISSING"));
		// Exempt is an entry too and leaves the paper out of the total
		enter(ec, sec5b, p[1], farah, "EXEMPT");
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"));
		r = admin.get("/api/v1/exams/classes/" + ec + "/results");
		assertThat(percentOf(r, farah)).isEqualTo("50.00");
		r.andExpect(jsonPath("$.students[?(@.studentId==%d)].totalMax".formatted(farah)).value(
				org.hamcrest.Matchers.contains("100")));
	}

	@Test
	void gradeOnlyClassesShowGrades() throws Exception {
		// QA-EXM-006
		TestDatabase.superuser().update("update school_class set grading_mode = 'GRADES' where id = ?", class5);
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		String url = "/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5a, p[0]);
		admin.put(url, marks(students.get(0), "Z")).andExpect(jsonPath("$.code").value("GRADE_INVALID"));
		admin.put(url, "{\"marks\":[{\"studentId\":%d,\"obtained\":\"80\"}]}".formatted(students.get(0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)].obtained".formatted(students.get(0))).value(
					org.hamcrest.Matchers.contains((Object) null)));
		enterAll(ec, p, "A", "B", "A", "A", "C", "B", "E", "D");
		var r = admin.get("/api/v1/exams/classes/" + ec + "/results")
			.andExpect(jsonPath("$.gradingMode").value("GRADES"));
		assertThat(percentOf(r, students.get(0))).isNull();
		r.andExpect(jsonPath("$.students[?(@.studentId==%d)].papers[*].grade".formatted(students.get(0))).value(
				org.hamcrest.Matchers.containsInAnyOrder("A", "B")));
	}

	@Test
	void reviewPublishAndUnlockKeepEveryVersion() throws Exception {
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "80", "80", "70", "70", "60", "60", "50", "50");
		// QA-EXM-008: Draft → Reviewed → Published; publishing needs its own permission
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(jsonPath("$.code").value("NOT_REVIEWED"));
		TestData.userWithPermissions(school, "coord", "coord-password-1", Permissions.EXAMS_VIEW,
				Permissions.EXAMS_REVIEW);
		ApiClient coordinator = new ApiClient(mvc);
		coordinator.login(school.code(), "coord", "coord-password-1").andExpect(status().isOk());
		coordinator.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVIEWED"))
			.andExpect(jsonPath("$.reviewedBy").value("User coord"));
		coordinator.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isForbidden());
		// A change after review sends the class back to draft
		enter(ec, sec5a, p[0], students.get(0), "81");
		admin.get("/api/v1/exams/classes/" + ec).andExpect(jsonPath("$.status").value("DRAFT"));
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk())
			.andExpect(jsonPath("$.publishedVersion").value(1));
		// Published marks are locked
		admin.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5a, p[0]),
				marks(students.get(0), "90"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESULT_PUBLISHED"));
		// QA-EXM-009: unlocking needs a reason and step-up; the correction is logged; version 2 is added
		admin.post("/api/v1/exams/classes/" + ec + "/unlock", "{\"reason\":\"Maths total wrong\"}")
			.andExpect(status().isForbidden());
		admin.stepUp(outbox);
		admin.post("/api/v1/exams/classes/" + ec + "/unlock", "{\"reason\":\" \"}")
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		admin.post("/api/v1/exams/classes/" + ec + "/unlock", "{\"reason\":\"Maths total wrong\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.unlockReason").value("Maths total wrong"));
		enter(ec, sec5a, p[0], students.get(0), "91");
		// A mark that was published can be corrected but not removed
		admin.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5a, p[0]),
				"{\"marks\":[{\"studentId\":%d}]}".formatted(students.get(1)))
			.andExpect(jsonPath("$.code").value("MARK_REQUIRED"));
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("""
				select count(*) from mark_change where school_id = ? and old_value = '81' and new_value = '91'
				   and reason = 'Maths total wrong'""", Integer.class, school.id())).isEqualTo(1);
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk())
			.andExpect(jsonPath("$.publishedVersion").value(2));
		admin.get("/api/v1/exams/classes/" + ec + "/versions").andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].version").value(2))
			.andExpect(jsonPath("$[0].students").value(5));
		var v1 = admin.get("/api/v1/exams/classes/" + ec + "/results?version=1");
		var v2 = admin.get("/api/v1/exams/classes/" + ec + "/results");
		assertThat(percentOf(v1, students.get(0))).isEqualTo("80.50");
		assertThat(percentOf(v2, students.get(0))).isEqualTo("85.50");
		v2.andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.live").value(false));
		// The student's profile shows the latest published version only
		admin.get("/api/v1/exams/students/" + students.get(0) + "/results").andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].version").value(2))
			.andExpect(jsonPath("$[0].percent").value("85.50"));
	}

	@Test
	void reportCardsPrintFromTheFrozenSnapshot() throws Exception {
		// QA-EXM-010/011
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "100", "90", "80", "80", "60", "60", "50", "50");
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk());
		// The school changes its grade scale after publishing: the published result keeps its grade
		var scales = admin.get("/api/v1/exams/grade-scales");
		List<Number> ids = ApiClient.json(scales, "$[?(@.kind=='MARKS')].id");
		admin.put("/api/v1/exams/grade-scales/" + ids.getFirst(), """
				{"bands":[{"grade":"A","minPercent":"96","passing":true},{"grade":"B","minPercent":"50","passing":true},
				          {"grade":"F","minPercent":"0","passing":false}]}""")
			.andExpect(status().isOk());
		var r = admin.get("/api/v1/exams/classes/" + ec + "/results");
		assertThat(gradeOf(r, students.get(0))).isEqualTo("A++");
		admin.get("/api/v1/exams/classes/" + ec + "/report-cards?sectionId=" + sec5a)
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/pdf"));
		admin.get("/api/v1/exams/classes/" + ec + "/report-cards?studentId=" + students.get(0))
			.andExpect(status().isOk());
		setting("report_cards.report_card_layout", "COMPACT");
		admin.get("/api/v1/exams/classes/" + ec + "/report-cards").andExpect(status().isOk());
		admin.get("/api/v1/exams/classes/" + ec + "/result-sheet").andExpect(status().isOk());
		admin.get("/api/v1/exams/classes/" + ec + "/datesheet").andExpect(status().isOk());
		admin.get("/api/v1/exams/classes/" + ec + "/datesheet.png").andExpect(status().isOk())
			.andExpect(content().contentType("image/png"));
		admin.get("/api/v1/exams/classes/" + ec + "/admit-cards?withholdDefaulters=true").andExpect(status().isOk());
		admin.get("/api/v1/exams/classes/" + ec + "/analysis").andExpect(status().isOk())
			.andExpect(jsonPath("$.students").value(5))
			.andExpect(jsonPath("$.subjects.length()").value(2));
	}

	@Test
	void cumulativeResultIsTheWeightedAverage() throws Exception {
		// QA-EXM-012: exam 1 (weight 1) 80%, exam 2 (weight 3) 60% → (80 + 180) ÷ 4 = 65.00
		long first = examClass("First term", true, "1");
		long second = examClass("Final", true, "3");
		long[] p1 = papers(first);
		long[] p2 = papers(second);
		enterAll(first, p1, "80", "80", "80", "80", "80", "80", "80", "80");
		enterAll(second, p2, "60", "60", "60", "60", "60", "60", "60", "60");
		for (long ec : new long[] { first, second }) {
			admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
			admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk());
		}
		admin.get("/api/v1/exams/cumulative?classId=%d&yearId=%d".formatted(class5, yearId))
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$[?(@.studentId==%d)].percent".formatted(students.get(0))).value(
					org.hamcrest.Matchers.contains("65.00")))
			.andExpect(jsonPath("$[?(@.studentId==%d)].grade".formatted(students.get(0))).value(
					org.hamcrest.Matchers.contains("C")));
	}

	@Test
	void resultSmsGoesOncePerStudent() throws Exception {
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "80", "80", "70", "70", "60", "60", "50", "50");
		admin.postWithKey("/api/v1/exams/classes/" + ec + "/send-results", "{}", "key-1")
			.andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));
		admin.post("/api/v1/exams/classes/" + ec + "/review").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/publish").andExpect(status().isOk());
		admin.post("/api/v1/exams/classes/" + ec + "/send-results", "{}")
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		admin.postWithKey("/api/v1/exams/classes/" + ec + "/send-results", "{}", "key-1")
			.andExpect(jsonPath("$.queued").value(5));
		admin.postWithKey("/api/v1/exams/classes/" + ec + "/send-results", "{}", "key-1")
			.andExpect(jsonPath("$.queued").value(0))
			.andExpect(jsonPath("$.alreadyQueued").value(5));
		admin.get("/api/v1/exams/messages").andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	void closedYearsAreReadOnly() throws Exception {
		long ec = examClass("Mid term", true, "1");
		long[] p = papers(ec);
		enterAll(ec, p, "80", "80", "70", "70", "60", "60", "50", "50");
		TestData.userWithPermissions(school, "office", "office-password-1", Permissions.EXAMS_VIEW,
				Permissions.EXAMS_MANAGE, Permissions.EXAMS_MARKS_ENTER, Permissions.EXAMS_REVIEW, Permissions.EXAMS_PRINT);
		ApiClient office = new ApiClient(mvc);
		office.login(school.code(), "office", "office-password-1").andExpect(status().isOk());
		TestDatabase.superuser().update("update academic_year set status = 'CLOSED' where id = ?", yearId);
		office.put("/api/v1/exams/classes/%d/marks?sectionId=%d&examSubjectId=%d".formatted(ec, sec5a, p[0]),
				marks(students.get(0), "90"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		office.post("/api/v1/exams/classes/" + ec + "/review").andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		office.put("/api/v1/exams/classes/" + ec + "/papers", "{\"papers\":[]}")
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		office.put("/api/v1/exams/classes/" + ec + "/remarks", "{\"remarks\":[]}")
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		// Reading and printing still work
		office.get("/api/v1/exams/classes/" + ec + "/results?sectionId=" + sec5a).andExpect(status().isOk());
		office.get("/api/v1/exams/classes/" + ec + "/result-sheet").andExpect(status().isOk());
	}

	@Test
	void anotherSchoolSeesNothing() throws Exception {
		long ec = examClass("Mid term", true, "1");
		SeededSchool other = TestData.school("EXO");
		TestData.admin(other, "admin", "admin-password-1");
		TestData.yearAroundToday(other, true);
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-1").andExpect(status().isOk());
		otherAdmin.get("/api/v1/exams").andExpect(jsonPath("$.length()").value(0));
		otherAdmin.get("/api/v1/exams/classes/" + ec).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/exams/classes/" + ec + "/results").andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/exams/classes/" + ec + "/report-cards").andExpect(status().isNotFound());
		// Each school has its own default scales
		otherAdmin.get("/api/v1/exams/grade-scales").andExpect(jsonPath("$.length()").value(2));
	}

	// ---------------------------------------------------------------- helpers

	private static String gradeOf(org.springframework.test.web.servlet.ResultActions r, long studentId)
			throws Exception {
		List<String> v = ApiClient.json(r, "$.students[?(@.studentId==%d)].grade".formatted(studentId));
		return v.getFirst();
	}

	private static String percentOf(org.springframework.test.web.servlet.ResultActions r, long studentId)
			throws Exception {
		List<String> v = ApiClient.json(r, "$.students[?(@.studentId==%d)].percent".formatted(studentId));
		return v.getFirst();
	}

	private static Integer positionOf(org.springframework.test.web.servlet.ResultActions r, long studentId)
			throws Exception {
		List<Integer> v = ApiClient.json(r, "$.students[?(@.studentId==%d)].position".formatted(studentId));
		return v.getFirst();
	}
}
