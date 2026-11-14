package com.selectcode.sms.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Admitting a class from an Excel sheet: all rows or none. */
@AutoConfigureMockMvc
class StudentImportIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	TestData.SeededSchool school;

	ApiClient admin;

	long sec5a;

	LocalDate admitted = LocalDate.now(ZoneId.of("Asia/Karachi")).minusDays(3);

	static final String HEAD = "Student name *|Gender *|Date of birth *|Father's name *|Father's mobile *|Mother's name|"
			+ "Mother's mobile|Father's CNIC|B-Form no.|Religion|Place of birth|Home address|Previous school";

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("IMP");
		TestData.admin(school, "admin", "admin-password-1");
		TestData.yearAroundToday(school, true);
		long class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	/** A sheet like a school would fill in: the heading row, then one student per row ("|" between cells). */
	static byte[] sheet(String heading, Object... rows) throws Exception {
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet s = book.createSheet("Students");
			String[] head = heading.split("\\|", -1);
			Row h = s.createRow(0);
			for (int i = 0; i < head.length; i++) {
				h.createCell(i).setCellValue(head[i]);
			}
			int r = 1;
			for (Object row : rows) {
				Row x = s.createRow(r++);
				if (row instanceof Object[] cells) {
					for (int i = 0; i < cells.length; i++) {
						if (cells[i] instanceof Number n) {
							x.createCell(i).setCellValue(n.doubleValue()); // e.g. a mobile typed as a number
						}
						else if (cells[i] instanceof LocalDate d) {
							var cell = x.createCell(i);
							cell.setCellValue(d);
							var style = book.createCellStyle();
							style.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy"));
							cell.setCellStyle(style);
						}
						else if (cells[i] != null) {
							x.createCell(i).setCellValue(cells[i].toString());
						}
					}
				}
			}
			book.write(out);
			return out.toByteArray();
		}
	}

	static Object[] row(Object... cells) {
		return cells;
	}

	Map<String, String> form() {
		return Map.of("sectionId", String.valueOf(sec5a), "admissionDate", admitted.toString());
	}

	long students() {
		return TestDatabase.superuser().queryForObject("select count(*) from student where school_id = ?", Long.class, school.id());
	}

	long familyOf(String name) {
		return TestDatabase.superuser().queryForObject("select family_id from student where school_id = ? and name = ?",
				Long.class, school.id(), name);
	}

	@Test
	void templateHasTheColumnsAndHelp() throws Exception {
		byte[] xlsx = admin.get("/api/v1/students/import/template").andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Row head = book.getSheet("Students").getRow(0);
			assertThat(head.getCell(0).getStringCellValue()).isEqualTo("Student name *");
			assertThat(head.getCell(4).getStringCellValue()).isEqualTo("Father's mobile *");
			assertThat(head.getCell(5).getStringCellValue()).isEqualTo("Mother's name");
			assertThat(book.getSheet("How to fill")).isNotNull();
		}
		// The template itself, uploaded empty, imports nothing
		admin.uploadForm("/api/v1/students/import/check", "t.xlsx", xlsx, form())
			.andExpect(jsonPath("$.ready").value(false))
			.andExpect(jsonPath("$.fileProblems[0]").value(org.hamcrest.Matchers.containsString("no students")));
	}

	@Test
	void aWholeClassIsAdmittedWithFamiliesAndGrNumbers() throws Exception {
		// A family already in the school (father's mobile 0300-5550001)
		admin.post("/api/v1/students", """
				{"student":{"name":"Older Brother","gender":"MALE"},"newFamily":{"fatherName":"Kashif","fatherPhone":"0300-5550001"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(sec5a, admitted)).andExpect(status().isCreated());
		byte[] xlsx = sheet(HEAD,
				row("Hamza Ali", "Male", "14-03-2016", "Ali Raza", "0300-1234567", "Sana", null, "35202-1234567-1"),
				row("Zainab Ali", "girl", LocalDate.of(2017, 5, 2), "Ali Raza", "0300-1234567"),
				row("Usman Kashif", "M", "02/09/2015", "Kashif", 3005550001L),
				row("Fatima Noor", "Female", "2016-01-20", "Noor Ahmed", "+92 301 7654321", null, null, null, "3520276543212", "Islam"));
		admin.uploadForm("/api/v1/students/import/check", "class.xlsx", xlsx, form())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ready").value(true))
			.andExpect(jsonPath("$.students").value(4))
			.andExpect(jsonPath("$.section").value("Class 5 - A"))
			.andExpect(jsonPath("$.rows[0].row").value(2))
			.andExpect(jsonPath("$.rows[0].family").value("New family"))
			.andExpect(jsonPath("$.rows[1].family").value("Same new family as Hamza Ali"))
			.andExpect(jsonPath("$.rows[2].family").value(org.hamcrest.Matchers.startsWith("Joins family F-")))
			.andExpect(jsonPath("$.rows[2].fatherPhone").value("03005550001"));
		assertThat(students()).isEqualTo(1); // checking admits nobody

		var r = admin.uploadForm("/api/v1/students/import", "class.xlsx", xlsx, form())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.admitted").value(4));
		List<String> grs = ApiClient.json(r, "$.students[*].grNo");
		assertThat(grs).containsExactly("002", "003", "004", "005");
		assertThat(students()).isEqualTo(5);
		assertThat(familyOf("Zainab Ali")).isEqualTo(familyOf("Hamza Ali"));
		assertThat(familyOf("Usman Kashif")).isEqualTo(familyOf("Older Brother"));
		assertThat(familyOf("Fatima Noor")).isNotEqualTo(familyOf("Hamza Ali"));
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("select dob from student where school_id = ? and name = 'Zainab Ali'", LocalDate.class,
				school.id())).isEqualTo(LocalDate.of(2017, 5, 2));
		assertThat(db.queryForObject("select b_form_no from student where school_id = ? and name = 'Fatima Noor'", String.class,
				school.id())).isEqualTo("35202-7654321-2");
		assertThat(db.queryForObject("select father_phone from family where id = ?", String.class, familyOf("Fatima Noor")))
			.isEqualTo("03017654321");
		assertThat(db.queryForObject("""
				select count(*) from enrolment e join student s on s.id = e.student_id
				where s.school_id = ? and e.section_id = ? and e.effective_from = ?""", Long.class, school.id(), sec5a,
				java.sql.Date.valueOf(admitted))).isEqualTo(5);
		assertThat(db.queryForObject("select count(*) from audit_log where school_id = ? and action = 'STUDENTS_IMPORTED'",
				Long.class, school.id())).isEqualTo(1);

		// The same sheet again: everybody is already admitted, nothing happens
		admin.uploadForm("/api/v1/students/import", "class.xlsx", xlsx, form())
			.andExpect(jsonPath("$.code").value("IMPORT_INVALID"));
		admin.uploadForm("/api/v1/students/import/check", "class.xlsx", xlsx, form())
			.andExpect(jsonPath("$.rows[0].problems[0]").value("Already admitted (GR no. 002)"));
		assertThat(students()).isEqualTo(5);
	}

	@Test
	void oneBadRowMeansNobodyIsImported() throws Exception {
		byte[] xlsx = sheet(HEAD,
				row("Good One", "Male", "14-03-2016", "Father One", "0300-1000001"),
				row("No Mobile", "Female", "14-03-2016", "Father Two", null),
				row("Good Two", "Female", "14-03-2016", "Father Three", "0300-1000003"),
				row(null, "Boy", "31-02-2016", "", "12345"),
				row("Good One", "Male", "14-03-2016", "Father One", "0300-1000001"),
				row(null, null, null, null, null),
				row("Future Child", "Male", "01-01-2099", "Father Six", "0300-1000006"));
		admin.uploadForm("/api/v1/students/import/check", "bad.xlsx", xlsx, form())
			.andExpect(jsonPath("$.ready").value(false))
			.andExpect(jsonPath("$.students").value(6)) // the empty row is skipped
			.andExpect(jsonPath("$.rowsWithProblems").value(4))
			.andExpect(jsonPath("$.rows[1].row").value(3))
			.andExpect(jsonPath("$.rows[1].problems[0]").value("Father's mobile is missing"))
			.andExpect(jsonPath("$.rows[3].problems").value(org.hamcrest.Matchers.hasItems("Student name is missing",
					"Father's name is missing")))
			.andExpect(jsonPath("$.rows[3].problems[*]").value(org.hamcrest.Matchers.hasItem(
					org.hamcrest.Matchers.containsString("is not a date"))))
			.andExpect(jsonPath("$.rows[3].problems[*]").value(org.hamcrest.Matchers.hasItem(
					org.hamcrest.Matchers.containsString("Father's mobile: enter a Pakistani number"))))
			.andExpect(jsonPath("$.rows[4].problems[0]").value("Same student as row 2"))
			.andExpect(jsonPath("$.rows[5].problems[0]").value("Date of birth is in the future"))
			.andExpect(jsonPath("$.rows[0].problems").isEmpty());
		admin.uploadForm("/api/v1/students/import", "bad.xlsx", xlsx, form())
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IMPORT_INVALID"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("4 rows have problems. Nothing was imported")));
		assertThat(students()).isZero();
	}

	@Test
	void theFileMustBeTheTemplate() throws Exception {
		admin.uploadForm("/api/v1/students/import/check", "x.xlsx", sheet("Name|Gender|Class", (Object) row("A", "Male", "5")), form())
			.andExpect(jsonPath("$.ready").value(false))
			.andExpect(jsonPath("$.fileProblems").value(org.hamcrest.Matchers.hasItem(
					"The column \"Date of birth\" is missing: use the template")));
		admin.uploadForm("/api/v1/students/import/check", "x.xlsx", "not excel".getBytes(), form())
			.andExpect(jsonPath("$.fileProblems[0]").value(org.hamcrest.Matchers.startsWith("This is not an Excel file")));
		admin.uploadForm("/api/v1/students/import/check", "x.xlsx", new byte[0], form())
			.andExpect(jsonPath("$.code").value("FILE_EMPTY"));
		// Columns in another order, headings without the stars, still work
		byte[] moved = sheet("Father's mobile|Student name|Father's name|Gender|Date of birth",
				(Object) row("0300-2000001", "Ali", "Raza", "Male", "01-01-2015"));
		admin.uploadForm("/api/v1/students/import", "moved.xlsx", moved, form()).andExpect(status().isCreated());
	}

	@Test
	void onlyStaffWhoMayAdmit() throws Exception {
		TestData.userWithPermissions(school, "viewer", "viewer-password-1", Permissions.STUDENTS_VIEW);
		ApiClient viewer = new ApiClient(mvc);
		viewer.login(school.code(), "viewer", "viewer-password-1").andExpect(status().isOk());
		viewer.get("/api/v1/students/import/template").andExpect(status().isForbidden());
		viewer.uploadForm("/api/v1/students/import", "a.xlsx", sheet(HEAD, (Object) row("A", "Male", "01-01-2015", "B", "0300-3000001")),
				form()).andExpect(status().isForbidden());
		assertThat(students()).isZero();
	}
}
