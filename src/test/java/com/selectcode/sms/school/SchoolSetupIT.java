package com.selectcode.sms.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** School profile, files, academic years, working days and holidays (QA-SET). */
@AutoConfigureMockMvc
class SchoolSetupIT extends IntegrationTest {

	/** Smallest valid PNG header bytes (enough for type detection). */
	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R' };

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("SET");
		TestData.admin(school, "admin", "admin-password-1");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long createYear(String name, String start, String end) throws Exception {
		var result = admin.post("/api/v1/school/years", """
				{"name":"%s","startDate":"%s","endDate":"%s"}""".formatted(name, start, end))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(result, "$.id")).longValue();
	}

	@Test
	void onlyOneCurrentYearAndOpeningANewYearClosesTheOldOne() throws Exception {
		long y1 = createYear("2026-2027", "2026-04-01", "2027-03-31");
		long y2 = createYear("2027-2028", "2027-04-01", "2028-03-31");
		admin.post("/api/v1/school/years/" + y1 + "/make-current").andExpect(jsonPath("$.status").value("CURRENT"));
		admin.post("/api/v1/school/years/" + y2 + "/make-current").andExpect(jsonPath("$.status").value("CURRENT"));
		admin.get("/api/v1/school/years")
			.andExpect(jsonPath("$[?(@.name=='2026-2027')].status").value("CLOSED"))
			.andExpect(jsonPath("$[?(@.name=='2027-2028')].status").value("CURRENT"));
		admin.post("/api/v1/school/years/" + y1 + "/make-current")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
	}

	@Test
	void yearValidation() throws Exception {
		createYear("2026-2027", "2026-04-01", "2027-03-31");
		admin.post("/api/v1/school/years", """
				{"name":"2026-2028","startDate":"2027-01-01","endDate":"2027-12-31"}""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("YEAR_OVERLAP"));
		admin.post("/api/v1/school/years", """
				{"name":"next year","startDate":"2028-04-01","endDate":"2029-03-31"}""")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("YEAR_NAME_INVALID"));
	}

	@Test
	void workingWeekDefaultsAndHolidays() throws Exception {
		long y = createYear("2026-2027", "2026-04-01", "2027-03-31");
		admin.get("/api/v1/school/years/" + y + "/working-days")
			.andExpect(jsonPath("$.SATURDAY").value("FULL"))
			.andExpect(jsonPath("$.SUNDAY").value("OFF"));
		admin.put("/api/v1/school/years/" + y + "/working-days", """
				{"MONDAY":"FULL","TUESDAY":"FULL","WEDNESDAY":"FULL","THURSDAY":"FULL","FRIDAY":"HALF",
				 "SATURDAY":"OFF","SUNDAY":"OFF"}""").andExpect(status().isOk()).andExpect(jsonPath("$.FRIDAY").value("HALF"));

		// QA-SET-006: summer vacation entered once.
		admin.post("/api/v1/school/years/" + y + "/days-off", """
				{"dateFrom":"2026-06-01","dateTo":"2026-08-14","title":"Summer vacation","type":"VACATION"}""")
			.andExpect(status().isCreated());
		admin.post("/api/v1/school/years/" + y + "/days-off", """
				{"dateFrom":"2027-06-01","dateTo":"2027-06-02","title":"Outside","type":"HOLIDAY"}""")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("DATES_OUTSIDE_YEAR"));
		admin.get("/api/v1/school/years/" + y + "/days-off").andExpect(jsonPath("$[0].title").value("Summer vacation"));
	}

	@Test
	void closedYearIsReadOnlyExceptForAuthorisedCorrections() throws Exception {
		long y1 = createYear("2025-2026", "2025-04-01", "2026-03-31");
		long y2 = createYear("2026-2027", "2026-04-01", "2027-03-31");
		admin.post("/api/v1/school/years/" + y1 + "/make-current").andExpect(status().isOk());
		admin.post("/api/v1/school/years/" + y2 + "/make-current").andExpect(status().isOk());

		TestData.userWithPermissions(school, "principal", "principal-pass1", Permissions.SCHOOL_CALENDAR_MANAGE);
		ApiClient principal = new ApiClient(mvc);
		principal.login(school.code(), "principal", "principal-pass1").andExpect(status().isOk());
		String holiday = """
				{"dateFrom":"2025-12-25","dateTo":"2025-12-25","title":"Quaid Day","type":"HOLIDAY"}""";
		principal.post("/api/v1/school/years/" + y1 + "/days-off", holiday)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("YEAR_CLOSED"));
		// The administrator holds school.year.correct_closed.
		admin.post("/api/v1/school/years/" + y1 + "/days-off", holiday).andExpect(status().isCreated());
	}

	@Test
	void profileWithUploadedLogo() throws Exception {
		var upload = admin.upload("/api/v1/files", "SCHOOL_LOGO", "logo.png", PNG)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType").value("image/png"));
		long logoId = ((Number) ApiClient.json(upload, "$.id")).longValue();

		var profile = admin.get("/api/v1/school/profile").andExpect(status().isOk());
		long version = ((Number) ApiClient.json(profile, "$.version")).longValue();
		admin.put("/api/v1/school/profile", """
				{"name":"NNS Secondary School","phonePrimary":"042-1234567","logoFileId":%d,
				 "signatures":[{"role":"Principal","name":"Mrs. Khan","title":"Principal"}],"version":%d}"""
			.formatted(logoId, version)).andExpect(status().isOk()).andExpect(jsonPath("$.logoFileId").value(logoId));
		// QA-SET-001 basis: the new phone is what documents will print.
		admin.get("/api/v1/school/profile").andExpect(jsonPath("$.phonePrimary").value("042-1234567"));
		// Saving with the old version is a conflict (someone else saved first).
		admin.put("/api/v1/school/profile", """
				{"name":"Other","version":%d}""".formatted(version))
			.andExpect(status().isConflict());

		admin.get("/api/v1/files/" + logoId).andExpect(status().isOk()).andExpect(content().bytes(PNG));
	}

	@Test
	void uploadsAreCheckedByContentAndIsolatedPerSchool() throws Exception {
		admin.upload("/api/v1/files", "SCHOOL_LOGO", "logo.png", "MZ fake exe".getBytes())
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
		var upload = admin.upload("/api/v1/files", "SCHOOL_STAMP", "stamp.png", PNG).andExpect(status().isOk());
		long fileId = ((Number) ApiClient.json(upload, "$.id")).longValue();

		var other = TestData.school("OTH");
		TestData.admin(other, "admin", "admin-password-2");
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-2").andExpect(status().isOk());
		// QA-TEN-007: another school's file is not found.
		otherAdmin.get("/api/v1/files/" + fileId).andExpect(status().isNotFound());

		// Users without profile edit permission cannot upload identity files.
		TestData.userWithPermissions(school, "teacher", "teacher-pass-1", Permissions.ACADEMICS_VIEW);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "teacher", "teacher-pass-1").andExpect(status().isOk());
		teacher.upload("/api/v1/files", "SCHOOL_LOGO", "logo.png", PNG).andExpect(status().isForbidden());
		assertThat(fileId).isPositive();
	}

	@Test
	void academicYearHeaderMustBelongToTheSchool() throws Exception {
		long y = createYear("2026-2027", "2026-04-01", "2027-03-31");
		var other = TestData.school("YRS");
		TestData.admin(other, "admin", "admin-password-3");
		ApiClient otherAdmin = new ApiClient(mvc);
		otherAdmin.login(other.code(), "admin", "admin-password-3").andExpect(status().isOk());
		otherAdmin.get("/api/v1/school/years/" + y + "/working-days").andExpect(status().isNotFound());
	}
}
