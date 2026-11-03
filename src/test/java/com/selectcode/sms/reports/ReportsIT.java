package com.selectcode.sms.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.io.ByteArrayInputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Student, teacher and class reports, access rules and exports (QA-RPT, QA-PLT-011 to 013). */
@AutoConfigureMockMvc
class ReportsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	long yearId;

	long class5;

	long sec5a;

	long sec5b;

	long maths;

	long staffId;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	/** This period: the last 7 days. Previous period: the 7 days before. */
	LocalDate from = today.minusDays(6);

	LocalDate prevDay = today.minusDays(10);

	List<Long> students = new ArrayList<>();

	long farah;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("RPT");
		TestData.admin(school, "admin", "admin-password-1");
		yearId = TestData.yearAroundToday(school, true);
		class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		sec5b = TestData.section(school, class5, "B");
		maths = TestData.subject(school, "Maths");
		staffId = TestData.teacher(school, "Ms Rabia");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		students.add(admit("Amna", sec5a));
		students.add(admit("Bilal", sec5a));
		farah = admit("Farah", sec5b);
		var db = TestDatabase.superuser();
		db.update("""
				insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id, effective_from)
				values (?, ?, ?, ?, ?, ?), (?, ?, ?, null, ?, ?)""", school.id(), yearId, sec5a, maths, staffId, Date.valueOf(today.minusDays(28)),
				school.id(), yearId, sec5a, staffId, Date.valueOf(today.minusDays(28)));
		// Amna: this period 3 present + 1 absent (75%); previous period 2 present (100%)
		attend(students.get(0), sec5a, today.minusDays(1), "P");
		attend(students.get(0), sec5a, today.minusDays(2), "P");
		attend(students.get(0), sec5a, today.minusDays(3), "P");
		attend(students.get(0), sec5a, today.minusDays(4), "A");
		attend(students.get(0), sec5a, prevDay, "P");
		attend(students.get(0), sec5a, prevDay.minusDays(1), "P");
		// Tests: maths this period Amna 80/100; previous period 60/100
		long now = test("Weekly", today.minusDays(2));
		long before = test("Earlier", prevDay);
		mark(now, students.get(0), "80");
		mark(now, students.get(1), "90");
		mark(before, students.get(0), "60");
	}

	long admit(String name, long sectionId) throws Exception {
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"FEMALE"},"newFamily":{"fatherName":"Father of %s"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(name, name, sectionId, today.minusDays(25)))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	void attend(long studentId, long sectionId, LocalDate on, String code) {
		TestDatabase.superuser().update("""
				insert into student_attendance (school_id, academic_year_id, att_date, section_id, student_id, status)
				values (?, ?, ?, ?, ?, ?)""", school.id(), yearId, Date.valueOf(on), sectionId, studentId, code);
	}

	long test(String name, LocalDate on) {
		return TestDatabase.superuser().queryForObject("""
				insert into class_test (school_id, academic_year_id, section_id, subject_id, name, test_date, max_marks)
				values (?, ?, ?, ?, ?, ?, 100) returning id""", Long.class, school.id(), yearId, sec5a, maths, name, Date.valueOf(on));
	}

	void mark(long testId, long studentId, String obtained) {
		TestDatabase.superuser().update("""
				insert into test_mark (school_id, class_test_id, student_id, obtained) values (?, ?, ?, ?::numeric)""", school.id(),
				testId, studentId, obtained);
	}

	String period() {
		return "from=%s&to=%s".formatted(from, today);
	}

	/** A row's cell of a named section, found by the row's first cell. */
	static String cell(ResultActions r, String section, String firstCell, int column) throws Exception {
		List<List<String>> rows = ApiClient.json(r, "$.sections[?(@.title=='%s')].rows[?(@[0]=='%s')]".formatted(section, firstCell));
		return rows.getFirst().get(column);
	}

	static List<String> titles(ResultActions r) throws Exception {
		return ApiClient.json(r, "$.sections[*].title");
	}

	@Test
	void studentReportComparesWithThePreviousPeriod() throws Exception {
		// QA-RPT-001 / 002
		var r = admin.get("/api/v1/reports/student?studentId=%d&%s".formatted(students.get(0), period())).andExpect(status().isOk());
		List<String> heading = ApiClient.json(r, "$.heading");
		assertThat(heading.getFirst()).contains("Amna").contains("Class 5 - A");
		assertThat(heading.get(1)).contains("Week").contains("Academic Year");
		assertThat(cell(r, "Attendance", "Present", 1)).isEqualTo("3");
		assertThat(cell(r, "Attendance", "Absent", 1)).isEqualTo("1");
		assertThat(cell(r, "Attendance", "Attendance %", 1)).isEqualTo("75.0");
		assertThat(cell(r, "Attendance", "Attendance %", 2)).isEqualTo("100.0");
		assertThat(cell(r, "Attendance", "Attendance %", 3)).isEqualTo("-25");
		assertThat(cell(r, "Marks by subject (%)", "Maths", 1)).isEqualTo("80.00");
		assertThat(cell(r, "Marks by subject (%)", "Maths", 2)).isEqualTo("60.00");
		assertThat(cell(r, "Marks by subject (%)", "Maths", 3)).isEqualTo("+20");
		r.andExpect(jsonPath("$.sections[?(@.title=='Tests in this period')].rows[0][6]").value(org.hamcrest.Matchers.contains("2")));
		assertThat(titles(r)).contains("Fee status today");
	}

	@Test
	void periodsStayInsideTheYear() throws Exception {
		LocalDate yearStart = TestDatabase.superuser().queryForObject("select start_date from academic_year where id = ?", Date.class, yearId).toLocalDate();
		admin.get("/api/v1/reports/student?studentId=%d&from=%s&to=%s".formatted(students.get(0), yearStart.minusDays(1), today))
			.andExpect(jsonPath("$.code").value("PERIOD_OUTSIDE_YEAR"));
		admin.get("/api/v1/reports/student?studentId=%d&from=%s&to=%s".formatted(students.get(0), today, from))
			.andExpect(jsonPath("$.code").value("DATES_INVALID"));
		admin.get("/api/v1/reports/class?%s".formatted(period())).andExpect(jsonPath("$.code").value("FIELD_REQUIRED"));
	}

	@Test
	void teachersGetTheirSectionsAndNoFeeData() throws Exception {
		// QA-RPT-003: even with fee permissions, an assigned-classes teacher sees no fee figures
		long userId = TestData.userWithPermissions(school, "rabia", "rabia-password-1", Permissions.REPORTS_VIEW, Permissions.FEES_VIEW);
		TestDatabase.superuser().update("update app_user set staff_id = ?, data_scope = 'ASSIGNED_CLASSES' where id = ?", staffId, userId);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "rabia", "rabia-password-1").andExpect(status().isOk());
		var student = teacher.get("/api/v1/reports/student?studentId=%d&%s".formatted(students.get(0), period())).andExpect(status().isOk());
		assertThat(titles(student)).doesNotContain("Fee status today");
		var section = teacher.get("/api/v1/reports/class?sectionId=%d&%s".formatted(sec5a, period())).andExpect(status().isOk());
		assertThat(section.andReturn().getResponse().getContentAsString()).doesNotContain("Fees outstanding");
		// The PDF and Excel come from the same object, so they cannot hold more
		byte[] xlsx = teacher.get("/api/v1/reports/class/file?sectionId=%d&%s&format=xlsx".formatted(sec5a, period()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		assertThat(allText(xlsx)).doesNotContain("Fees outstanding").contains("Average attendance %");
		// Another section does not exist for them; a class report covers only their section
		teacher.get("/api/v1/reports/student?studentId=%d&%s".formatted(farah, period())).andExpect(status().isNotFound());
		teacher.get("/api/v1/reports/class?sectionId=%d&%s".formatted(sec5b, period())).andExpect(status().isNotFound());
		var wholeClass = teacher.get("/api/v1/reports/class?classId=%d&%s".formatted(class5, period())).andExpect(status().isOk());
		assertThat(cell(wholeClass, "Summary", "Students at the end of the period", 1)).isEqualTo("2");
		// Teacher reports are for the principal
		teacher.get("/api/v1/reports/teacher?staffId=%d&%s".formatted(staffId, period())).andExpect(status().isForbidden());
		ApiClient other = new ApiClient(mvc);
		TestData.userWithPermissions(school, "nobody", "nobody-password-1");
		other.login(school.code(), "nobody", "nobody-password-1").andExpect(status().isOk());
		other.get("/api/v1/reports/class?sectionId=%d&%s".formatted(sec5a, period())).andExpect(status().isForbidden());
	}

	@Test
	void classReportHasEnrolmentAttendanceAndGrades() throws Exception {
		attend(students.get(1), sec5a, today.minusDays(1), "A");
		var r = admin.get("/api/v1/reports/class?sectionId=%d&%s".formatted(sec5a, period())).andExpect(status().isOk());
		assertThat(cell(r, "Summary", "Students at the end of the period", 1)).isEqualTo("2");
		// 3 present of 5 marked this period
		assertThat(cell(r, "Summary", "Average attendance %", 1)).isEqualTo("60.0");
		// (80 + 90) of 200
		assertThat(cell(r, "Summary", "Average test %", 1)).isEqualTo("85.00");
		assertThat(cell(r, "Summary", "Average test %", 2)).isEqualTo("60.00");
		assertThat(cell(r, "Test averages", "Maths", 2)).isEqualTo("85.00");
		// Amna 80 → A, Bilal 90 → A+
		assertThat(cell(r, "Grade distribution", "A", 1)).isEqualTo("1");
		assertThat(cell(r, "Grade distribution", "A+", 1)).isEqualTo("1");
		assertThat(cell(r, "Summary", "Fees outstanding today", 1)).isEqualTo("0");
	}

	@Test
	void teacherReportShowsMarksOnTimeAndRegisters() throws Exception {
		var db = TestDatabase.superuser();
		// Weekly: complete, entered straight away → on time. Late test: complete, entered 10 days after.
		long late = test("Late one", today.minusDays(1));
		mark(late, students.get(0), "50");
		mark(late, students.get(1), "50");
		db.update("update test_mark set first_entered_at = first_entered_at + interval '10 days' where class_test_id = ?", late);
		long partial = test("Partial", today.minusDays(3));
		mark(partial, students.get(0), "50");
		// A correction today must not make the on-time test late: entered_at changes, first_entered_at stays
		db.update("update test_mark set entered_at = now() + interval '30 days' where class_test_id = (select id from class_test where school_id = ? and name = 'Weekly')", school.id());
		db.update("""
				insert into staff_attendance (school_id, att_date, staff_id, status) values (?, ?, ?, 'L'), (?, ?, ?, 'P')""", school.id(),
				Date.valueOf(today.minusDays(1)), staffId, school.id(), Date.valueOf(today.minusDays(2)), staffId);
		var r = admin.get("/api/v1/reports/teacher?staffId=%d&%s".formatted(staffId, period())).andExpect(status().isOk());
		assertThat(cell(r, "Attendance", "Late", 1)).isEqualTo("1");
		assertThat(cell(r, "Attendance", "Attendance %", 1)).isEqualTo("100.0");
		List<List<String>> tests = ApiClient.json(r, "$.sections[?(@.title=='Marks entry')].rows[*]");
		assertThat(tests).extracting(t -> t.get(3) + ":" + t.get(5)).containsExactlyInAnyOrder("Weekly:On time", "Late one:Late", "Partial:Not complete");
		List<String> note = ApiClient.json(r, "$.sections[?(@.title=='Marks entry')].note");
		assertThat(note.getFirst()).startsWith("1 of 3 tests");
		List<List<String>> registers = ApiClient.json(r, "$.sections[?(@.title=='Attendance registers')].rows[*]");
		assertThat(registers).hasSize(1);
		assertThat(registers.getFirst().get(0)).isEqualTo("Class 5 - A");
		List<String> roles = ApiClient.json(r, "$.sections[?(@.title=='Classes and subjects')].rows[*][1]");
		assertThat(roles).containsExactlyInAnyOrder("Maths", "Class teacher");
	}

	@Test
	void filesMatchTheScreenWithNumericCells() throws Exception {
		// QA-RPT-004
		String url = "/api/v1/reports/student/file?studentId=%d&%s&format=".formatted(students.get(0), period());
		byte[] pdf = admin.get(url + "pdf").andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
		byte[] xlsx = admin.get(url + "xlsx").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = book.getSheetAt(0);
			assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("STUDENT REPORT");
			assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).contains("Academic Year");
			Row present = rowStarting(sheet, "Present");
			assertThat(present.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
			assertThat(present.getCell(1).getNumericCellValue()).isEqualTo(3d);
			Row outstanding = rowStarting(sheet, "Outstanding");
			assertThat(sheet.getRow(outstanding.getRowNum() + 1).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
		}
		admin.get(url + "doc").andExpect(jsonPath("$.code").value("FORMAT_INVALID"));
		long exports = TestDatabase.superuser().queryForObject("select count(*) from audit_log where school_id = ? and action = 'REPORT_EXPORTED'", Long.class, school.id());
		assertThat(exports).isEqualTo(2);
	}

	@Test
	void tablesOnScreenExportWithMoneyAsNumbers() throws Exception {
		byte[] xlsx = admin.post("/api/v1/reports/tables/file?format=xlsx", """
				{"title":"Fee defaulters","filters":["Class 5"],"tables":[{"title":"Families","columns":["Family","GR","Owed","Paid %"],
				 "rows":[["=HYPERLINK(1)","00123","Rs. 1,234.50","12.5%"],["Khan","77","Rs. 3,500","—"]]}]}""")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = book.getSheetAt(0);
			Row first = rowStarting(sheet, "=HYPERLINK(1)");
			assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
			assertThat(first.getCell(1).getStringCellValue()).isEqualTo("00123");
			assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(1234.5d);
			assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(0.125d);
			Row second = sheet.getRow(first.getRowNum() + 1);
			assertThat(second.getCell(1).getNumericCellValue()).isEqualTo(77d);
			assertThat(second.getCell(3).getStringCellValue()).isEqualTo("—");
		}
		admin.post("/api/v1/reports/tables/file?format=pdf", """
				{"title":"T","tables":[{"columns":["A"],"rows":[["x"]]}]}""").andExpect(status().isOk());
	}

	@Test
	void anotherSchoolGetsNothing() throws Exception {
		SeededSchool other = TestData.school("RPO");
		TestData.admin(other, "admin", "admin-password-1");
		TestData.yearAroundToday(other, true);
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-1").andExpect(status().isOk());
		otherAdmin.get("/api/v1/reports/student?studentId=%d&%s".formatted(students.get(0), period())).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/reports/class?sectionId=%d&%s".formatted(sec5a, period())).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/reports/teacher?staffId=%d&%s".formatted(staffId, period())).andExpect(status().isNotFound());
		otherAdmin.get("/api/v1/platform/reports/schools?%s".formatted(period())).andExpect(status().isForbidden());
		admin.get("/api/v1/platform/reports/schools/file?%s&format=xlsx".formatted(period())).andExpect(status().isForbidden());
	}

	@Test
	void superAdminComparesSchools() throws Exception {
		// QA-PLT-011 / 013: per school figures and a grand total
		SeededSchool other = TestData.school("RPO");
		long otherYear = TestData.yearAroundToday(other, true);
		String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
		String email = "owner-" + school.code().toLowerCase() + "@test.local";
		TestData.superAdmin(email, "owner-password-1", secret);
		ApiClient owner = new ApiClient(mvc);
		owner.platformLogin(email, "owner-password-1", secret).andExpect(status().isOk());
		var r = owner.get("/api/v1/platform/reports/schools?%s".formatted(period())).andExpect(status().isOk());
		List<Integer> mine = ApiClient.json(r, "$.schools[?(@.schoolId==%d)].students".formatted(school.id()));
		assertThat(mine).containsExactly(3);
		List<String> attendance = ApiClient.json(r, "$.schools[?(@.schoolId==%d)].attendancePercent".formatted(school.id()));
		assertThat(attendance).containsExactly("75.0");
		List<Integer> theirs = ApiClient.json(r, "$.schools[?(@.schoolId==%d)].students".formatted(other.id()));
		assertThat(theirs).containsExactly(0);
		assertThat(otherYear).isPositive();
		Integer total = ApiClient.json(r, "$.total.students");
		List<Integer> all = ApiClient.json(r, "$.schools[*].students");
		assertThat(total).isEqualTo(all.stream().mapToInt(Integer::intValue).sum());
		byte[] xlsx = owner.get("/api/v1/platform/reports/schools/file?%s&format=xlsx".formatted(period())).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			assertThat(book.getNumberOfSheets()).isEqualTo(all.size() + 1);
			assertThat(book.getSheetAt(0).getSheetName()).isEqualTo("All schools");
			assertThat(rowStarting(book.getSheetAt(0), school.code() + " School")).isNull();
		}
		owner.get("/api/v1/platform/reports/schools/file?%s&format=pdf".formatted(period())).andExpect(status().isOk());
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from platform_audit_log where action = 'CROSS_SCHOOL_REPORT_EXPORTED'", Long.class)).isGreaterThanOrEqualTo(2);
	}

	private static Row rowStarting(Sheet sheet, String text) {
		for (Row row : sheet) {
			Cell c = row.getCell(0);
			if (c != null && c.getCellType() == CellType.STRING && text.equals(c.getStringCellValue())) {
				return row;
			}
		}
		return null;
	}

	private static String allText(byte[] xlsx) throws Exception {
		StringBuilder sb = new StringBuilder();
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			for (Sheet sheet : book) {
				for (Row row : sheet) {
					for (Cell c : row) {
						if (c.getCellType() == CellType.STRING) {
							sb.append(c.getStringCellValue()).append('\n');
						}
					}
				}
			}
		}
		return sb.toString();
	}
}
