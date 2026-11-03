package com.selectcode.sms.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.messaging.SmsSegments;
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

/** Dashboard panels, urgent lines, office tools and messaging (QA-DSH, QA-COM, QA-OFF). */
@AutoConfigureMockMvc
class OfficeIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Autowired
	MessageOutbox messages;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long class5;

	long sec5a;

	long sec5b;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	List<Long> students = new ArrayList<>();

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("OFF");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		sec5b = TestData.section(school, class5, "B");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		students.add(admit("Amna", "MALE", sec5a, "03001111111"));
		students.add(admit("Bilal", "MALE", sec5a, "03002222222"));
		students.add(admit("Farah", "FEMALE", sec5b, "03003333333"));
	}

	long admit(String name, String gender, long sectionId, String phone) throws Exception {
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"%s"},"newFamily":{"fatherName":"Father of %s","fatherPhone":"%s"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(name, gender, name, phone, sectionId, today.minusDays(3)))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	ApiClient user(String username, String... permissions) throws Exception {
		TestData.userWithPermissions(school, username, username + "-password-1", permissions);
		ApiClient client = new ApiClient(mvc);
		client.login(school.code(), username, username + "-password-1").andExpect(status().isOk());
		return client;
	}

	// -------------------------------------------------------------- dashboard

	@Test
	void panelsFollowPermissionsAndScope() throws Exception {
		// QA-DSH-001 totals
		admin.get("/api/v1/dashboard").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.students").value(3))
			.andExpect(jsonPath("$.totals.boys").value(2))
			.andExpect(jsonPath("$.totals.girls").value(1))
			.andExpect(jsonPath("$.totals.families").value(3))
			.andExpect(jsonPath("$.fees.collectedToday").exists())
			.andExpect(jsonPath("$.register.length()").value(3))
			.andExpect(jsonPath("$.register[0].kind").value("ADMITTED"));
		// QA-DSH-003: an accountant-like user gets fee figures, no attendance or logins
		ApiClient accountant = user("acc", Permissions.FEES_REPORTS_VIEW, Permissions.OFFICE_TASKS);
		accountant.get("/api/v1/dashboard").andExpect(status().isOk())
			.andExpect(jsonPath("$.fees.outstanding").exists())
			.andExpect(jsonPath("$.attendance").doesNotExist())
			.andExpect(jsonPath("$.totals").doesNotExist())
			.andExpect(jsonPath("$.logins").doesNotExist())
			.andExpect(jsonPath("$.tasks").isArray());
		// A teacher sees only their own section's attendance, no school totals
		long staffId = TestData.teacher(school, "Ms Rabia");
		long userId = TestData.userWithPermissions(school, "rabia", "rabia-password-1", Permissions.ATTENDANCE_VIEW,
				Permissions.STUDENTS_VIEW, Permissions.FEES_REPORTS_VIEW);
		var db = TestDatabase.superuser();
		db.update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", staffId, userId);
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, null, ?, ?)""", school.id(), yearId, sec5a, staffId, Date.valueOf(today.minusDays(28)));
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		teacher.get("/api/v1/dashboard").andExpect(status().isOk())
			.andExpect(jsonPath("$.attendance.sections").value(1))
			.andExpect(jsonPath("$.attendance.staff").doesNotExist())
			.andExpect(jsonPath("$.attendance.repeatedAbsences").doesNotExist())
			.andExpect(jsonPath("$.totals").doesNotExist())
			.andExpect(jsonPath("$.fees").doesNotExist())
			.andExpect(jsonPath("$.register").doesNotExist());
	}

	@Test
	void figuresCanBeMasked() throws Exception {
		// QA-DSH-004
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/general.mask_dashboard_figures", "{\"value\":true,\"reason\":\"Reception screen\"}")
			.andExpect(status().isOk());
		admin.get("/api/v1/dashboard").andExpect(jsonPath("$.masked").value(true))
			.andExpect(jsonPath("$.totals").doesNotExist())
			.andExpect(jsonPath("$.fees").doesNotExist());
		admin.get("/api/v1/dashboard?reveal=true").andExpect(jsonPath("$.masked").value(false))
			.andExpect(jsonPath("$.totals.students").value(3))
			.andExpect(jsonPath("$.fees").exists());
	}

	@Test
	void urgentLinesAreAtMostFiveInOrder() throws Exception {
		var db = TestDatabase.superuser();
		// Make today a working day, whatever day the test runs on
		boolean working = db.queryForObject("select extract(isodow from current_date) <> 7", Boolean.class);
		// Complaint open 8 days, enquiry with no follow-up, fee follow-up overdue, two overdue tasks, staff unmarked
		admin.post("/api/v1/office/complaints", "{\"complainant\":\"Mr Khan\",\"subject\":\"Van late\"}")
			.andExpect(status().isCreated());
		db.update("update complaint set opened_at = now() - interval '8 days' where school_id = ?", school.id());
		long family = db.queryForObject("select family_id from student where id = ?", Long.class, students.get(0));
		db.update("insert into fee_followup (school_id, family_id, remark, next_date) values (?, ?, 'Call', ?)", school.id(),
				family, Date.valueOf(today.minusDays(2)));
		db.update("""
				insert into enquiry (school_id, registration_no, enquiry_date, source, child_name, status_id)
				select ?, 'E-1', ?, 'WALK_IN', 'Sara', id from enquiry_status where school_id = ? and system_code = 'NEW'""",
				school.id(), Date.valueOf(today.minusDays(9)), school.id());
		admin.post("/api/v1/office/tasks", "{\"description\":\"Board forms\",\"deadline\":\"%s\"}".formatted(today.minusDays(1)))
			.andExpect(status().isCreated());
		TestData.teacher(school, "Mr Staff");
		var r = admin.get("/api/v1/dashboard").andExpect(status().isOk());
		List<String> codes = ApiClient.json(r, "$.urgent[*].code");
		assertThat(codes.size()).isLessThanOrEqualTo(5);
		if (working) {
			// QA-DSH-002: 2 sections + staff + complaint + enquiry + fee + task = 7 pending; the top 5 show, in order
			assertThat(codes).containsExactly("ATTENDANCE", "ATTENDANCE", "STAFF_ATTENDANCE", "COMPLAINTS", "ENQUIRIES");
		}
		else {
			// QA-DSH-005: a day off gives no attendance warning
			assertThat(codes).containsExactly("COMPLAINTS", "ENQUIRIES", "FEE_FOLLOWUPS", "TASKS");
		}
	}

	@Test
	void aDayOffGivesNoAttendanceWarning() throws Exception {
		// QA-DSH-005
		TestDatabase.superuser().update("""
				insert into calendar_day_off (school_id, academic_year_id, date_from, date_to, title, type)
				values (?, ?, ?, ?, 'Holiday', 'HOLIDAY')""", school.id(), yearId, Date.valueOf(today), Date.valueOf(today));
		var r = admin.get("/api/v1/dashboard").andExpect(status().isOk())
			.andExpect(jsonPath("$.attendance.workingDay").value(false))
			.andExpect(jsonPath("$.attendance.missing.length()").value(0));
		List<String> codes = ApiClient.json(r, "$.urgent[*].code");
		assertThat(codes).doesNotContain("ATTENDANCE", "STAFF_ATTENDANCE");
	}

	@Test
	void favouritesBelongToTheUser() throws Exception {
		admin.put("/api/v1/dashboard/favourites",
				"{\"pages\":[{\"href\":\"/app/fees/defaulters\",\"label\":\"Fee defaulters\"}]}")
			.andExpect(jsonPath("$[0].label").value("Fee defaulters"));
		admin.get("/api/v1/dashboard").andExpect(jsonPath("$.favourites[0].href").value("/app/fees/defaulters"));
		admin.put("/api/v1/dashboard/favourites", "{\"pages\":[{\"href\":\"https://evil.test\",\"label\":\"X\"}]}")
			.andExpect(jsonPath("$.code").value("PAGE_INVALID"));
		user("other").get("/api/v1/dashboard").andExpect(jsonPath("$.favourites.length()").value(0));
	}

	// ----------------------------------------------------------------- office

	@Test
	void tasksAreSeenByThoseInvolved() throws Exception {
		ApiClient colleague = user("colleague", Permissions.OFFICE_TASKS);
		ApiClient stranger = user("stranger", Permissions.OFFICE_TASKS);
		long colleagueId = TestDatabase.superuser().queryForObject(
				"select id from app_user where school_id = ? and username = 'colleague'", Long.class, school.id());
		var r = admin.post("/api/v1/office/tasks", """
				{"description":"Submit board forms","deadline":"%s","priority":"URGENT","shareWith":[%d]}""".formatted(
				today.minusDays(1), colleagueId)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.overdue").value(true))
			.andExpect(jsonPath("$.sharedWith[0].name").value("User colleague"));
		long id = ((Number) ApiClient.json(r, "$.id")).longValue();
		colleague.get("/api/v1/office/tasks").andExpect(jsonPath("$.length()").value(1));
		stranger.get("/api/v1/office/tasks").andExpect(jsonPath("$.length()").value(0));
		stranger.post("/api/v1/office/tasks/" + id + "/complete").andExpect(status().isNotFound());
		colleague.post("/api/v1/office/tasks/" + id + "/complete").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.completedBy").value("User colleague"))
			.andExpect(jsonPath("$.overdue").value(false));
		admin.get("/api/v1/office/tasks").andExpect(jsonPath("$.length()").value(0));
		admin.get("/api/v1/office/tasks?includeDone=true").andExpect(jsonPath("$.length()").value(1));
		user("nothing").get("/api/v1/office/tasks").andExpect(status().isForbidden());
	}

	@Test
	void complaintsKeepAHistory() throws Exception {
		var r = admin.post("/api/v1/office/complaints", """
				{"complainant":"Mr Khan","phone":"03001111111","subject":"Van late","details":"Three days"}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("OPEN"));
		long id = ((Number) ApiClient.json(r, "$.id")).longValue();
		long adminId = TestDatabase.superuser().queryForObject(
				"select id from app_user where school_id = ? and username = 'admin'", Long.class, school.id());
		admin.post("/api/v1/office/complaints/" + id + "/assign", "{\"userId\":%d}".formatted(adminId))
			.andExpect(jsonPath("$.assignedTo").value("User admin"));
		admin.post("/api/v1/office/complaints/" + id + "/remarks", "{\"text\":\"Spoke to the driver\"}")
			.andExpect(jsonPath("$.remarks.length()").value(2));
		admin.post("/api/v1/office/complaints/" + id + "/close", "{\"text\":\" \"}")
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		admin.post("/api/v1/office/complaints/" + id + "/close", "{\"text\":\"Route changed\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.remarks[2].text").value("Closed: Route changed"));
		admin.post("/api/v1/office/complaints/" + id + "/close", "{\"text\":\"Again\"}").andExpect(status().isConflict());
		admin.get("/api/v1/dashboard").andExpect(jsonPath("$.complaints.closed").value(1))
			.andExpect(jsonPath("$.complaints.open").value(0));
		// Viewing and managing are separate; closed years are read-only
		ApiClient viewer = user("viewer", Permissions.OFFICE_COMPLAINTS_VIEW);
		viewer.get("/api/v1/office/complaints").andExpect(jsonPath("$.length()").value(1));
		viewer.post("/api/v1/office/complaints/" + id + "/remarks", "{\"text\":\"No\"}").andExpect(status().isForbidden());
		TestDatabase.superuser().update("update academic_year set status = 'CLOSED' where id = ?", yearId);
		ApiClient office = user("office", Permissions.OFFICE_COMPLAINTS_MANAGE);
		office.post("/api/v1/office/complaints/" + id + "/remarks", "{\"text\":\"Late note\"}")
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
	}

	@Test
	void circularsEventsAndVisitors() throws Exception {
		admin.post("/api/v1/office/circulars", """
				{"title":"Winter timing","body":"School starts at 8:30."}""").andExpect(status().isCreated());
		admin.post("/api/v1/office/events", """
				{"title":"Sports Day","dateFrom":"%s","dateTo":"%s","type":"EVENT"}""".formatted(today.plusDays(2), today.plusDays(3)))
			.andExpect(status().isCreated());
		admin.post("/api/v1/office/events", """
				{"title":"Bad","dateFrom":"%s","dateTo":"%s"}""".formatted(today.plusDays(3), today.plusDays(2)))
			.andExpect(jsonPath("$.code").value("DATES_INVALID"));
		admin.get("/api/v1/office/events?from=%s&to=%s".formatted(today, today.plusDays(2))).andExpect(jsonPath("$.length()").value(1));
		admin.get("/api/v1/office/events?from=%s&to=%s".formatted(today, today.plusDays(1))).andExpect(jsonPath("$.length()").value(0));
		admin.post("/api/v1/office/visitors", "{\"name\":\"Inspector\",\"purpose\":\"Visit\"}").andExpect(status().isCreated());
		admin.post("/api/v1/office/visitors", "{\"name\":\"Later\",\"date\":\"%s\"}".formatted(today.plusDays(1)))
			.andExpect(jsonPath("$.code").value("DATE_IN_FUTURE"));
		admin.get("/api/v1/dashboard")
			.andExpect(jsonPath("$.visitorsToday").value(1))
			.andExpect(jsonPath("$.events[0].title").value("Sports Day"))
			.andExpect(jsonPath("$.circulars[0].title").value("Winter timing"));
		// Everyone reads; only some post
		ApiClient reader = user("reader", Permissions.OFFICE_CIRCULARS_VIEW, Permissions.OFFICE_EVENTS_VIEW);
		reader.get("/api/v1/office/circulars").andExpect(jsonPath("$.length()").value(1));
		reader.post("/api/v1/office/circulars", "{\"title\":\"X\",\"body\":\"Y\"}").andExpect(status().isForbidden());
		reader.get("/api/v1/office/visitors?date=" + today).andExpect(status().isForbidden());
	}

	@Test
	void birthdaysAndOneWishAYear() throws Exception {
		TestDatabase.superuser().update("update student set dob = ? where id = ?", Date.valueOf(today.plusDays(2).minusYears(10)),
				students.get(0));
		admin.get("/api/v1/dashboard").andExpect(jsonPath("$.birthdays.length()").value(1))
			.andExpect(jsonPath("$.birthdays[0].name").value("Amna"))
			.andExpect(jsonPath("$.birthdays[0].daysLeft").value(2));
		admin.post("/api/v1/office/birthdays/STUDENT/" + students.get(0) + "/wish").andExpect(jsonPath("$.queued").value(true));
		admin.post("/api/v1/office/birthdays/STUDENT/" + students.get(0) + "/wish").andExpect(jsonPath("$.alreadySent").value(true));
		admin.get("/api/v1/office/birthdays?days=7").andExpect(jsonPath("$[0].wished").value(true));
	}

	// -------------------------------------------------------------- messaging

	@Test
	void templatesFillPlaceholdersAndCheckThem() throws Exception {
		// QA-COM-001
		admin.put("/api/v1/communication/templates/WELCOME", "{\"body\":\"{school}: Welcome {name}! {nonsense}\"}")
			.andExpect(jsonPath("$.code").value("PLACEHOLDER_UNKNOWN"));
		admin.put("/api/v1/communication/templates/WELCOME", "{\"body\":\"{school}: Welcome {name} to {class}.\"}")
			.andExpect(jsonPath("$.customised").value(true));
		admin.post("/api/v1/communication/welcome/" + students.get(0)).andExpect(jsonPath("$.queued").value(true));
		admin.post("/api/v1/communication/welcome/" + students.get(0)).andExpect(jsonPath("$.alreadySent").value(true));
		var log = admin.get("/api/v1/communication/log?purpose=WELCOME").andExpect(jsonPath("$.messages.length()").value(1));
		String body = ApiClient.json(log, "$.messages[0].body");
		assertThat(body).isEqualTo("OFF School: Welcome Amna to Class 5. - Administration");
		admin.get("/api/v1/dashboard").andExpect(jsonPath("$.register[?(@.studentId==%d)].welcomeSent".formatted(students.get(0)))
			.value(org.hamcrest.Matchers.contains(true)));
		admin.delete("/api/v1/communication/templates/WELCOME").andExpect(jsonPath("$.customised").value(false));
		admin.get("/api/v1/communication/templates").andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	void previewShowsCostAndSendGoesOncePerPhone() throws Exception {
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/messages.sms_cost_per_part", "{\"value\":\"1.50\",\"reason\":\"Rate\"}").andExpect(status().isOk());
		// A brother on the same phone as Amna, and a family with no phone
		admin.post("/api/v1/students", """
				{"student":{"name":"Zain","gender":"MALE"},"newFamily":{"fatherName":"Same Phone","fatherPhone":"03001111111"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(sec5a, today.minusDays(3))).andExpect(status().isCreated());
		admin.post("/api/v1/students", """
				{"student":{"name":"Noor","gender":"FEMALE"},"newFamily":{"fatherName":"No Phone"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(sec5a, today.minusDays(3))).andExpect(status().isCreated());
		String longText = "Dear {name}, ".concat("x".repeat(170));
		String request = "{\"audience\":\"SECTION\",\"sectionId\":%d,\"message\":\"%s\"}".formatted(sec5a, longText);
		// QA-COM-002: parts and cost before sending; nothing queued
		admin.post("/api/v1/communication/preview", request).andExpect(status().isOk())
			.andExpect(jsonPath("$.recipients").value(2))
			.andExpect(jsonPath("$.noPhone").value(1))
			.andExpect(jsonPath("$.encoding").value("GSM-7"))
			.andExpect(jsonPath("$.parts").value(2))
			.andExpect(jsonPath("$.estimatedCost").value("6.00"));
		admin.get("/api/v1/communication/log").andExpect(jsonPath("$.messages.length()").value(0));
		admin.post("/api/v1/communication/send", request).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		admin.postWithKey("/api/v1/communication/send", request, "k-1").andExpect(jsonPath("$.queued").value(2));
		admin.postWithKey("/api/v1/communication/send", request, "k-1").andExpect(jsonPath("$.queued").value(0))
			.andExpect(jsonPath("$.alreadyQueued").value(2));
		// Other audiences
		admin.post("/api/v1/communication/preview", "{\"audience\":\"ALL_FAMILIES\",\"message\":\"Hi\"}")
			.andExpect(jsonPath("$.recipients").value(3));
		admin.post("/api/v1/communication/preview", "{\"audience\":\"CLASS\",\"classId\":%d,\"message\":\"Hi\"}".formatted(class5))
			.andExpect(jsonPath("$.recipients").value(3));
		admin.post("/api/v1/communication/preview", "{\"audience\":\"STUDENTS\",\"studentIds\":[%d],\"message\":\"Hi\"}".formatted(students.get(2)))
			.andExpect(jsonPath("$.recipients").value(1));
		admin.post("/api/v1/communication/preview", "{\"audience\":\"NOBODY\",\"message\":\"Hi\"}")
			.andExpect(jsonPath("$.code").value("AUDIENCE_INVALID"));
		user("reader", Permissions.MESSAGES_VIEW).postWithKey("/api/v1/communication/send", request, "k-2")
			.andExpect(status().isForbidden());
	}

	@Test
	void segmentsAreCounted() {
		assertThat(SmsSegments.count("x".repeat(160)).segments()).isEqualTo(1);
		assertThat(SmsSegments.count("x".repeat(161)).segments()).isEqualTo(2);
		assertThat(SmsSegments.count("x".repeat(306)).segments()).isEqualTo(2);
		assertThat(SmsSegments.count("x".repeat(307)).segments()).isEqualTo(3);
		// Extended characters count twice
		assertThat(SmsSegments.count("[".repeat(81)).segments()).isEqualTo(2);
		// A curly quote switches the whole text to UCS-2
		assertThat(SmsSegments.count("It’s " + "x".repeat(70)).encoding()).isEqualTo("UCS-2");
		assertThat(SmsSegments.count("x".repeat(70) + "’").segments()).isEqualTo(2);
	}

	@Test
	void dashboardLoadsQuicklyWithAFullSchool() throws Exception {
		// QA-DSH-006: 2,400 students with a month of attendance
		var db = TestDatabase.superuser();
		db.update("""
				insert into family (school_id, family_no, father_name, father_phone)
				select ?, 'P-' || g, 'Father ' || g, '0301' || lpad(g::text, 7, '0') from generate_series(1, 2400) g""", school.id());
		db.update("""
				insert into student (school_id, gr_no, name, gender, dob, admission_date, family_id)
				select ?, 'P-' || f.id, 'Student ' || f.id, 'MALE', current_date - 3650 - f.id::int % 365, current_date - 60, f.id
				from family f where f.school_id = ? and f.family_no like 'P-%'""", school.id(), school.id());
		db.update("""
				insert into enrolment (school_id, student_id, academic_year_id, class_id, section_id, effective_from, kind)
				select ?, s.id, ?, ?, ?, current_date - 60, 'ADMISSION'
				from student s where s.school_id = ? and s.gr_no like 'P-%'""", school.id(), yearId, class5, sec5a, school.id());
		db.update("""
				insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
				select ?, ?, current_date - d, ?, s.id, case when s.id % 20 = 0 then 'A' else 'P' end
				from student s cross join generate_series(1, 28) d
				where s.school_id = ? and s.gr_no like 'P-%'""", school.id(), yearId, sec5a, school.id());
		db.update("analyze student_attendance");
		admin.get("/api/v1/dashboard").andExpect(status().isOk());
		long start = System.nanoTime();
		admin.get("/api/v1/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.totals.students").value(2403));
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("Dashboard with 2,400 students took " + millis + " ms");
		assertThat(millis).isLessThan(1500);
	}

	@Test
	void anotherSchoolSeesNothing() throws Exception {
		var r = admin.post("/api/v1/office/complaints", "{\"complainant\":\"Mr Khan\",\"subject\":\"Van\"}");
		long complaint = ((Number) ApiClient.json(r, "$.id")).longValue();
		var t = admin.post("/api/v1/office/tasks", "{\"description\":\"Mine\"}");
		long task = ((Number) ApiClient.json(t, "$.id")).longValue();
		admin.post("/api/v1/office/circulars", "{\"title\":\"Ours\",\"body\":\"Text\"}");
		SeededSchool other = TestData.school("OFO");
		TestData.admin(other, "admin", "admin-password-1");
		TestData.yearAroundToday(other, true);
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-1").andExpect(status().isOk());
		otherAdmin.get("/api/v1/office/complaints/" + complaint).andExpect(status().isNotFound());
		otherAdmin.post("/api/v1/office/tasks/" + task + "/complete").andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/office/circulars").andExpect(jsonPath("$.length()").value(0));
		otherAdmin.post("/api/v1/communication/welcome/" + students.get(0)).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/dashboard").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.students").value(0))
			.andExpect(jsonPath("$.complaints.total").value(0))
			.andExpect(jsonPath("$.circulars.length()").value(0));
	}
}
