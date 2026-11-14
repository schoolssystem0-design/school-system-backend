package com.selectcode.sms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.attendance.app.AttendanceReports;
import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Registers, corrections, leave, percentages, repeated absences, staff attendance → payroll (QA-ATT). */
@AutoConfigureMockMvc
class AttendanceIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long class4;

	long sec4a;

	long sec4b;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	List<Long> students = new ArrayList<>();

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("ATT");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		class4 = TestData.schoolClass(school, "Class 4", 4);
		sec4a = TestData.section(school, class4, "A");
		sec4b = TestData.section(school, class4, "B");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		for (String name : List.of("Amna", "Bilal", "Danish", "Eman")) {
			students.add(admit(name, sec4a));
		}
		admit("Farah", sec4b);
	}

	long admit(String name, long sectionId) throws Exception {
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"FEMALE"},"newFamily":{"fatherName":"Father of %s","fatherPhone":"0300%07d"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(name, name, Math.abs(name.hashCode()) % 10_000_000,
				sectionId, today.minusDays(28))).andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	/** The last n working days (Monday–Saturday) before today, oldest first. */
	List<LocalDate> pastWorkingDays(int n) {
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate d = today.minusDays(1); days.size() < n; d = d.minusDays(1)) {
			if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
				days.addFirst(d);
			}
		}
		return days;
	}

	LocalDate lastSunday() {
		LocalDate d = today.minusDays(1);
		while (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
			d = d.minusDays(1);
		}
		return d;
	}

	String marks(Object... pairs) {
		StringBuilder sb = new StringBuilder("{\"marks\":[");
		for (int i = 0; i < pairs.length; i += 2) {
			sb.append(i == 0 ? "" : ",").append("{\"studentId\":%d,\"status\":\"%s\"}".formatted(pairs[i], pairs[i + 1]));
		}
		return sb.append("]}").toString();
	}

	@Test
	void registerStartsPresentAndSavingCompletesIt() throws Exception {
		// QA-ATT-001
		LocalDate day = pastWorkingDays(1).getFirst();
		admin.get("/api/v1/attendance/registers/" + sec4a + "?date=" + day)
			.andExpect(jsonPath("$.rows.length()").value(4))
			.andExpect(jsonPath("$.rows[?(@.status=='P')].studentId").isArray())
			.andExpect(jsonPath("$.completed").value(false));
		// QA-ATT-003: both sections are missing their register
		admin.get("/api/v1/attendance/missing?date=" + day).andExpect(jsonPath("$.length()").value(2));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day,
				marks(students.get(0), "A", students.get(1), "A", students.get(2), "L"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(true))
			.andExpect(jsonPath("$.totals.A").value(2))
			.andExpect(jsonPath("$.totals.L").value(1))
			.andExpect(jsonPath("$.totals.P").value(1));
		admin.get("/api/v1/attendance/missing?date=" + day).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].sectionId").value(sec4b));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + today.plusDays(1), marks())
			.andExpect(jsonPath("$.code").value("DATE_IN_FUTURE"));
	}

	@Test
	void closedDaysAndEarlierChangesNeedAReason() throws Exception {
		// QA-ATT-002: Sunday is not a working day
		LocalDate sunday = lastSunday();
		TestData.userWithPermissions(school, "teacher", "teacher-password-1", Permissions.ATTENDANCE_VIEW,
				Permissions.ATTENDANCE_MARK);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "teacher", "teacher-password-1").andExpect(status().isOk());
		teacher.put("/api/v1/attendance/registers/" + sec4a + "?date=" + sunday, "{\"reason\":\"Extra class\"}")
			.andExpect(jsonPath("$.code").value("NOT_WORKING_DAY"));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + sunday, "{}")
			.andExpect(jsonPath("$.code").value("NOT_WORKING_DAY"));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + sunday, "{\"reason\":\"Make-up day\"}")
			.andExpect(status().isOk());
		// QA-ATT-004: changing an earlier day's saved status
		LocalDate day = pastWorkingDays(1).getFirst();
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day, marks(students.get(0), "A"));
		teacher.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day,
				marks(students.get(0), "P").replace("]}", "],\"reason\":\"School trip\"}"))
			.andExpect(jsonPath("$.code").value("CORRECTION_REASON_REQUIRED"));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day, marks(students.get(0), "P"))
			.andExpect(jsonPath("$.code").value("CORRECTION_REASON_REQUIRED"));
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day,
				marks(students.get(0), "P").replace("]}", "],\"reason\":\"School trip\"}")).andExpect(status().isOk());
		admin.get("/api/v1/attendance/corrections?from=%s&to=%s&personType=STUDENT".formatted(day, day))
			.andExpect(jsonPath("$[0].oldStatus").value("A"))
			.andExpect(jsonPath("$[0].newStatus").value("P"))
			.andExpect(jsonPath("$[0].reason").value("School trip"))
			.andExpect(jsonPath("$[0].correctedBy").value("User admin"));
	}

	@Test
	void teachersTakeOnlyTheirClassTeacherRegisters() throws Exception {
		long staffId = TestData.teacher(school, "Ms Rabia");
		long userId = TestData.userWithPermissions(school, "rabia", "rabia-password-1", Permissions.ATTENDANCE_VIEW,
				Permissions.ATTENDANCE_MARK);
		var db = TestDatabase.superuser();
		db.update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", staffId, userId);
		long subject = TestData.subject(school, "English");
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, null, ?, ?), (?, ?, ?, ?, ?, ?)""", school.id(), yearId, sec4a, staffId,
				Date.valueOf(today.minusDays(28)), school.id(), yearId, sec4b, subject, staffId,
				Date.valueOf(today.minusDays(28)));
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		LocalDate day = pastWorkingDays(1).getFirst();
		teacher.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day, marks(students.get(0), "A"))
			.andExpect(status().isOk());
		// Subject teacher of 4-B: may see, may not take the register
		teacher.get("/api/v1/attendance/registers/" + sec4b + "?date=" + day).andExpect(status().isOk())
			.andExpect(jsonPath("$.canMark").value(false));
		teacher.put("/api/v1/attendance/registers/" + sec4b + "?date=" + day, marks()).andExpect(status().isForbidden());
		// Missing list shows only their sections
		teacher.get("/api/v1/attendance/missing?date=" + day).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].sectionId").value(sec4b));
		teacher.get("/api/v1/attendance/leave").andExpect(status().isForbidden());
	}

	@Test
	void percentagesFollowTheFormula() throws Exception {
		// QA-ATT-006 by the formula, and on stored data
		assertThat(AttendanceReports.percent(20, 1, 0, 22, 1)).isEqualTo("100.0");
		assertThat(AttendanceReports.percent(7, 0, 1, 10, 0)).isEqualTo("75.0");
		assertThat(AttendanceReports.percent(0, 0, 0, 3, 3)).isNull();
		List<LocalDate> days = pastWorkingDays(10);
		var db = TestDatabase.superuser();
		for (int i = 0; i < 10; i++) {
			String first = i < 8 ? "P" : i == 8 ? "L" : "LV";
			String second = i < 7 ? "P" : i == 7 ? "HD" : "A";
			db.update("""
					insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
					values (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)""", school.id(), yearId, Date.valueOf(days.get(i)), sec4a,
					students.get(0), first, school.id(), yearId, Date.valueOf(days.get(i)), sec4a, students.get(1), second);
		}
		// A record on a Sunday never counts
		db.update("""
				insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
				values (?, ?, ?, ?, ?, 'A')""", school.id(), yearId, Date.valueOf(lastSunday()), sec4a, students.get(0));
		String period = "from=%s&to=%s".formatted(days.getFirst(), days.getLast());
		admin.get("/api/v1/attendance/reports/students?sectionId=%d&%s".formatted(sec4a, period))
			.andExpect(jsonPath("$[?(@.studentId==%d)].percent".formatted(students.get(0))).value("100.0"))
			.andExpect(jsonPath("$[?(@.studentId==%d)].percent".formatted(students.get(1))).value("75.0"));
		admin.get("/api/v1/attendance/reports/low?" + period).andExpect(jsonPath("$.length()").value(0));
		admin.get("/api/v1/attendance/reports/full?sectionId=%d&%s".formatted(sec4a, period))
			.andExpect(jsonPath("$.length()").value(0));
		admin.get("/api/v1/attendance/reports/sections?" + period)
			.andExpect(jsonPath("$[?(@.sectionId==%d)].absences".formatted(sec4a)).value(2));
	}

	@Test
	void repeatedAbsencesSkipDaysOff() throws Exception {
		// QA-ATT-007: the last 3 working days absent, across the weekend
		List<LocalDate> days = pastWorkingDays(4);
		var db = TestDatabase.superuser();
		for (int i = 0; i < 4; i++) {
			db.update("""
					insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
					values (?, ?, ?, ?, ?, ?)""", school.id(), yearId, Date.valueOf(days.get(i)), sec4a, students.get(2),
					i == 0 ? "P" : "A");
		}
		admin.get("/api/v1/attendance/reports/repeated?asOf=" + today)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].studentId").value(students.get(2)))
			.andExpect(jsonPath("$[0].daysInARow").value(3))
			.andExpect(jsonPath("$[0].since").value(days.get(1).toString()));
	}

	@Test
	void approvedLeaveTurnsMarkedAbsencesIntoLeave() throws Exception {
		// QA-ATT-008
		List<LocalDate> days = pastWorkingDays(2);
		for (LocalDate d : days) {
			admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + d, marks(students.get(3), "A"));
		}
		TestData.userWithPermissions(school, "office", "office-password-1", Permissions.ATTENDANCE_LEAVE_MANAGE,
				Permissions.ATTENDANCE_VIEW);
		ApiClient office = new ApiClient(mvc);
		office.login(school.code(), "office", "office-password-1").andExpect(status().isOk());
		var leave = office.post("/api/v1/attendance/leave", """
				{"personType":"STUDENT","studentId":%d,"leaveType":"SICK","dateFrom":"%s","dateTo":"%s","reason":"Fever"}"""
			.formatted(students.get(3), days.getFirst(), days.getLast())).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.workingDays").value(2));
		long leaveId = ((Number) ApiClient.json(leave, "$.id")).longValue();
		office.post("/api/v1/attendance/leave/" + leaveId + "/approve").andExpect(status().isForbidden());
		admin.post("/api/v1/attendance/leave/" + leaveId + "/approve").andExpect(jsonPath("$.status").value("APPROVED"));
		admin.get("/api/v1/attendance/registers/%d?date=%s".formatted(sec4a, days.getFirst()))
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)].status".formatted(students.get(3))).value("LV"));
		admin.get("/api/v1/attendance/corrections?from=%s&to=%s&personId=%d".formatted(days.getFirst(), days.getLast(),
				students.get(3))).andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].reason").value("Approved leave #" + leaveId));
		office.post("/api/v1/attendance/leave", """
				{"personType":"STUDENT","studentId":%d,"leaveType":"SICK","dateFrom":"%s","dateTo":"%s","reason":"Again"}"""
			.formatted(students.get(3), days.getFirst(), days.getFirst())).andExpect(status().isConflict());
	}

	@Test
	void staffLateByCategoryAndApprovedMonthFeedsPayroll() throws Exception {
		// QA-ATT-009: teachers late after 07:30, other staff after 07:00
		long teacher = TestData.teacher(school, "Mr Kamran");
		long guard = TestDatabase.superuser().queryForObject("""
				insert into staff (school_id, employee_no, category, name, designation, joining_date)
				values (?, 'G-1', 'OTHER', 'Guard Aslam', 'Guard', current_date - 400) returning id""", Long.class,
				school.id());
		LocalDate day = pastWorkingDays(1).getFirst();
		admin.put("/api/v1/attendance/staff/day?date=" + day, """
				{"marks":[{"staffId":%d,"status":"P","arrivalTime":"07:50"},{"staffId":%d,"status":"P","arrivalTime":"07:20"}]}"""
			.formatted(teacher, guard))
			.andExpect(jsonPath("$.rows[?(@.staffId==%d)].status".formatted(teacher)).value("L"))
			.andExpect(jsonPath("$.rows[?(@.staffId==%d)].status".formatted(guard)).value("L"));
		LocalDate other = pastWorkingDays(2).getFirst();
		admin.put("/api/v1/attendance/staff/day?date=" + other, """
				{"marks":[{"staffId":%d,"status":"P","arrivalTime":"07:25"},{"staffId":%d,"status":"A"}]}"""
			.formatted(teacher, guard))
			.andExpect(jsonPath("$.rows[?(@.staffId==%d)].status".formatted(teacher)).value("P"));
		// QA-ATT-010: the approved month locks attendance and is what payroll uses
		YearMonth month = YearMonth.from(day);
		admin.stepUp(outbox);
		admin.post("/api/v1/attendance/staff/month/approve", "{\"month\":\"%s\"}".formatted(month))
			.andExpect(jsonPath("$.code").value("UNMARKED_DAYS"));
		admin.post("/api/v1/attendance/staff/month/approve",
				"{\"month\":\"%s\",\"unmarkedAsPresent\":true}".formatted(month))
			.andExpect(jsonPath("$[?(@.staffId==%d)].approved".formatted(guard)).value(true));
		admin.put("/api/v1/attendance/staff/day?date=" + day, """
				{"marks":[{"staffId":%d,"status":"A"}]}""".formatted(teacher))
			.andExpect(jsonPath("$.code").value("MONTH_APPROVED"));
		TestDatabase.superuser().update("""
				insert into salary_profile (school_id, staff_id, effective_from, basic_salary, reason)
				values (?, ?, ?, 30000, 'Test')""", school.id(), guard, Date.valueOf(month.minusMonths(2).atDay(1)));
		var sheet = admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(month)).andExpect(status().isCreated());
		boolean sameDay = day.equals(other);
		sheet.andExpect(jsonPath("$.lines[?(@.staffId==%d)].attendanceSource".formatted(guard)).value("ATTENDANCE"))
			.andExpect(jsonPath("$.lines[?(@.staffId==%d)].absentDays".formatted(guard)).value(sameDay ? 0 : 1))
			.andExpect(jsonPath("$.lines[?(@.staffId==%d)].lateDays".formatted(guard)).value(1));
		long sheetId = ((Number) ApiClient.json(sheet, "$.id")).longValue();
		admin.post("/api/v1/payroll/sheets/" + sheetId + "/approve").andExpect(status().isOk());
		admin.post("/api/v1/attendance/staff/month/reopen", "{\"month\":\"%s\",\"reason\":\"Fix\"}".formatted(month))
			.andExpect(jsonPath("$.code").value("PAYROLL_APPROVED"));
	}

	@Test
	void movedStudentsAppearOnlyForTheirDatesAndTheMonthlyRegisterPrints() throws Exception {
		// QA-ATT-011
		List<LocalDate> days = pastWorkingDays(6);
		long moved = students.get(0);
		admin.post("/api/v1/students/" + moved + "/move", """
				{"toSectionId":%d,"effectiveDate":"%s","reason":"Balance"}""".formatted(sec4b, days.get(3)))
			.andExpect(status().isOk());
		admin.get("/api/v1/attendance/registers/%d?date=%s".formatted(sec4a, days.get(1)))
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)]".formatted(moved)).isNotEmpty());
		admin.get("/api/v1/attendance/registers/%d?date=%s".formatted(sec4a, days.get(4)))
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)]".formatted(moved)).isEmpty());
		admin.get("/api/v1/attendance/registers/%d?date=%s".formatted(sec4b, days.get(4)))
			.andExpect(jsonPath("$.rows[?(@.studentId==%d)]".formatted(moved)).isNotEmpty());
		byte[] pdf = admin.get("/api/v1/attendance/registers/%d/monthly?month=%s".formatted(sec4a, YearMonth.from(days.get(1))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
	}

	@Test
	void bulkCorrectionAndAbsentFine() throws Exception {
		List<LocalDate> days = pastWorkingDays(2);
		for (LocalDate d : days) {
			admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + d,
					marks(students.get(0), "A", students.get(1), "A"));
		}
		// QA-ATT-005 style: a class trip over two days, absent → present for one student's class
		admin.post("/api/v1/attendance/corrections/bulk", """
				{"from":"%s","to":"%s","sectionId":%d,"fromStatus":"A","toStatus":"P","reason":"Class trip"}"""
			.formatted(days.getFirst(), days.getFirst(), sec4a)).andExpect(jsonPath("$.changed").value(2));
		// QA-FEE-020: absent fine for the remaining absences (status A on working days only)
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/fees.absent_fine_amount", "{\"value\":\"50\",\"reason\":\"Policy\"}")
			.andExpect(status().isOk());
		YearMonth month = YearMonth.from(days.getLast());
		admin.postWithKey("/api/v1/fees/absent-fine-runs", "{\"month\":\"%s\"}".formatted(month), "fine-1")
			.andExpect(jsonPath("$.billsCreated").value(2))
			.andExpect(jsonPath("$.totalBilled").value("100.00"));
		admin.postWithKey("/api/v1/fees/absent-fine-runs", "{\"month\":\"%s\"}".formatted(month), "fine-2")
			.andExpect(jsonPath("$.billsCreated").value(0)).andExpect(jsonPath("$.alreadyBilled").value(2));
	}

	@Test
	void anotherSchoolCannotSeeRegisters() throws Exception {
		LocalDate day = pastWorkingDays(1).getFirst();
		admin.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day, marks(students.get(0), "A"));
		SeededSchool other = TestData.school("ATB");
		TestData.admin(other, "admin", "admin-password-2");
		TestData.yearAroundToday(other, true);
		ApiClient intruder = new ApiClient(mvc);
		intruder.login(other.code(), "admin", "admin-password-2").andExpect(status().isOk());
		intruder.get("/api/v1/attendance/registers/" + sec4a + "?date=" + day).andExpect(status().isNotFound());
		intruder.put("/api/v1/attendance/registers/" + sec4a + "?date=" + day, marks()).andExpect(status().isNotFound());
		intruder.get("/api/v1/attendance/reports/students?from=%s&to=%s".formatted(day, day))
			.andExpect(jsonPath("$.length()").value(0));
	}
}
