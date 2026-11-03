package com.selectcode.sms.admissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Enquiry to enrolment (QA-ADM). */
@AutoConfigureMockMvc
class AdmissionsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	long classId;

	long sectionId;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("ADM");
		TestData.admin(school, "admin", "admin-password-1");
		TestData.yearAroundToday(school, true);
		classId = TestData.schoolClass(school, "Class 3", 3);
		sectionId = TestData.section(school, classId, "A");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long createEnquiry(String child, String phone) throws Exception {
		var result = admin.post("/api/v1/admissions/enquiries", """
				{"source":"PHONE","childName":"%s","gender":"MALE","dob":"2019-05-14","classAppliedId":%d,
				 "parentName":"Imran Ali","parentPhone":"%s"}""".formatted(child, classId, phone))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(result, "$.id")).longValue();
	}

	long statusId(String code) {
		return TestDatabase.superuser()
			.queryForObject("select id from enquiry_status where school_id = ? and system_code = ?", Long.class,
					school.id(), code);
	}

	@Test
	void enquiryGetsNumberStatusAndRemarks() throws Exception {
		// QA-ADM-001
		long id = createEnquiry("Hamza Imran", "0300-1112223");
		admin.get("/api/v1/admissions/enquiries/" + id)
			.andExpect(jsonPath("$.registrationNo").value("ENQ-00001"))
			.andExpect(jsonPath("$.status.systemCode").value("NEW"))
			.andExpect(jsonPath("$.details.parentPhone").value("03001112223"))
			.andExpect(jsonPath("$.className").value("Class 3"));
		admin.post("/api/v1/admissions/enquiries/" + id + "/remarks", "{\"text\":\"Father will visit Saturday\"}")
			.andExpect(status().isCreated());
		admin.post("/api/v1/admissions/enquiries/" + id + "/status",
				"{\"statusId\":%d,\"remark\":\"Called back\"}".formatted(statusId("FOLLOW_UP")))
			.andExpect(jsonPath("$.status.systemCode").value("FOLLOW_UP"));
		admin.get("/api/v1/admissions/enquiries/" + id + "/remarks")
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[*].text").value(hasItem("Father will visit Saturday")));
		admin.get("/api/v1/admissions/enquiries?statusId=" + statusId("FOLLOW_UP"))
			.andExpect(jsonPath("$.items[0].childName").value("Hamza Imran"))
			.andExpect(jsonPath("$.items[0].remarks").value(2));
	}

	@Test
	void entryTestMarksAreValidated() throws Exception {
		long id = createEnquiry("Sara", "0301-2223334");
		admin.put("/api/v1/admissions/enquiries/" + id + "/test", "{\"obtained\":60,\"total\":50}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("MARKS_INVALID"));
		// FR 4.4 example: 42/50
		admin.put("/api/v1/admissions/enquiries/" + id + "/test", "{\"testDate\":\"2026-03-10\",\"obtained\":42,\"total\":50}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.test.obtained").value(42.0));
	}

	@Test
	void duplicatesAreFoundByPhoneAndSimilarName() throws Exception {
		// QA-ADM-003
		createEnquiry("Muhammad Ahmed Khan", "0333-5556667");
		admin.get("/api/v1/admissions/duplicates?phone=+92 333 5556667")
			.andExpect(jsonPath("$[0].kind").value("ENQUIRY"))
			.andExpect(jsonPath("$[0].reason").value("Same parent phone"));
		admin.get("/api/v1/admissions/duplicates?name=Muhammad Ahmad Khan&dob=2019-05-14")
			.andExpect(jsonPath("$[0].reason").value("Similar name"));
		admin.get("/api/v1/admissions/duplicates?name=Zainab&phone=0345-0000000").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void approvedEnquiryIsEnrolledInOneStep() throws Exception {
		// QA-ADM-004
		long id = createEnquiry("Ayesha Noor", "0321-9998887");
		String enrol = """
				{"grNo":"2553","student":{"name":"Ayesha Noor","gender":"FEMALE","dob":"2019-05-14"},
				 "newFamily":{"fatherName":"Noor Ahmed","fatherPhone":"0321-9998887","fatherCnic":"35202-1234567-1"},
				 "sectionId":%d}""".formatted(sectionId);
		admin.post("/api/v1/admissions/enquiries/" + id + "/enrol", enrol)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("NOT_APPROVED"));

		admin.post("/api/v1/admissions/enquiries/" + id + "/status", "{\"statusId\":%d}".formatted(statusId("APPROVED")))
			.andExpect(status().isOk());
		var student = admin.post("/api/v1/admissions/enquiries/" + id + "/enrol", enrol)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.grNo").value("2553"))
			.andExpect(jsonPath("$.currentClass.className").value("Class 3"))
			.andExpect(jsonPath("$.family.fatherName").value("Noor Ahmed"))
			.andExpect(jsonPath("$.enquiryId").value(id));
		long studentId = ((Number) ApiClient.json(student, "$.id")).longValue();

		admin.get("/api/v1/admissions/enquiries/" + id)
			.andExpect(jsonPath("$.status.systemCode").value("ENROLLED"))
			.andExpect(jsonPath("$.studentId").value(studentId));
		// An enrolled enquiry is closed.
		admin.post("/api/v1/admissions/enquiries/" + id + "/status", "{\"statusId\":%d}".formatted(statusId("FOLLOW_UP")))
			.andExpect(status().isConflict());
		// The new family is found by phone next time (FR 4.5).
		admin.get("/api/v1/admissions/duplicates?phone=03219998887")
			.andExpect(jsonPath("$[*].kind").value(hasItem("STUDENT")))
			.andExpect(jsonPath("$[*].kind").value(hasItem("FAMILY")));
	}

	@Test
	void enrolmentIsAtomic() throws Exception {
		// QA-ADM-005: a duplicate GR number fails the whole enrolment; no family or student is left behind.
		long id = createEnquiry("Bilal", "0300-7778889");
		admin.post("/api/v1/admissions/enquiries/" + id + "/status", "{\"statusId\":%d}".formatted(statusId("APPROVED")));
		admin.post("/api/v1/students", """
				{"grNo":"100","student":{"name":"First","gender":"MALE"},"newFamily":{"fatherName":"X"},"sectionId":%d}"""
			.formatted(sectionId)).andExpect(status().isCreated());
		Long familiesBefore = TestDatabase.superuser()
			.queryForObject("select count(*) from family where school_id = ?", Long.class, school.id());
		admin.post("/api/v1/admissions/enquiries/" + id + "/enrol", """
				{"grNo":"100","student":{"name":"Bilal","gender":"MALE"},"newFamily":{"fatherName":"Y"},"sectionId":%d}"""
			.formatted(sectionId)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GR_TAKEN"));
		assertThat(TestDatabase.superuser()
			.queryForObject("select count(*) from family where school_id = ?", Long.class, school.id()))
			.isEqualTo(familiesBefore);
		admin.get("/api/v1/admissions/enquiries/" + id).andExpect(jsonPath("$.status.systemCode").value("APPROVED"));
	}

	@Test
	void customStatusesAndPrintableForm() throws Exception {
		admin.post("/api/v1/admissions/statuses", "{\"name\":\"Test scheduled\",\"colour\":\"#0ea5e9\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.systemCode").doesNotExist());
		admin.post("/api/v1/admissions/statuses", "{\"name\":\"Bad\",\"colour\":\"blue\"}")
			.andExpect(status().isUnprocessableContent());
		admin.put("/api/v1/admissions/content", "{\"kind\":\"TERMS\",\"body\":\"Fees are paid by the 10th.\"}")
			.andExpect(status().isNoContent());
		long id = createEnquiry("Hira", "0302-1231234");
		byte[] pdf = admin.get("/api/v1/admissions/form?enquiryId=" + id)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
	}
}
