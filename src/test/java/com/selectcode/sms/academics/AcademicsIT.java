package com.selectcode.sms.academics;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Classes, sections, subjects and maximum marks (QA-ACA). */
@AutoConfigureMockMvc
class AcademicsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("ACA");
		TestData.admin(school, "admin", "admin-password-1");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return ((Number) ApiClient.json(result, "$.id")).longValue();
	}

	@Test
	void classesSectionsAndOrdering() throws Exception {
		long pg = id(admin.post("/api/v1/academics/classes", "{\"name\":\"Play Group\",\"gradingMode\":\"GRADES\"}")
			.andExpect(status().isCreated()));
		long c8 = id(admin.post("/api/v1/academics/classes", "{\"name\":\"Class 8\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.shortName").value("8")));
		admin.post("/api/v1/academics/classes", "{\"name\":\"class 8\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NAME_TAKEN"));

		admin.post("/api/v1/academics/classes/" + c8 + "/sections", "{\"name\":\"A\"}").andExpect(status().isCreated());
		admin.post("/api/v1/academics/classes/" + c8 + "/sections", "{\"name\":\"B\"}").andExpect(status().isCreated());
		admin.post("/api/v1/academics/classes/" + c8 + "/sections", "{\"name\":\"a\"}").andExpect(status().isConflict());

		// QA-ACA-001: reorder.
		admin.put("/api/v1/academics/classes/order", "{\"classIds\":[" + c8 + "," + pg + "]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Class 8"))
			.andExpect(jsonPath("$[0].sections.length()").value(2));
		admin.put("/api/v1/academics/classes/order", "{\"classIds\":[" + c8 + "]}")
			.andExpect(status().isUnprocessableContent());

		// QA-ACA-002 style: a used class cannot be deleted, only deactivated.
		admin.get("/api/v1/academics/classes/" + c8 + "/usage").andExpect(jsonPath("$[0].what").value("Sections"));
		admin.delete("/api/v1/academics/classes/" + c8)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IN_USE"));
		admin.post("/api/v1/academics/classes/" + c8 + "/status", "{\"status\":\"INACTIVE\"}")
			.andExpect(jsonPath("$.status").value("INACTIVE"));
		admin.delete("/api/v1/academics/classes/" + pg).andExpect(status().isNoContent());
	}

	@Test
	void classSubjectsWithMaximumAndPassingMarks() throws Exception {
		long year = ((Number) ApiClient.json(admin.post("/api/v1/school/years", """
				{"name":"2026-2027","startDate":"2026-04-01","endDate":"2027-03-31"}""").andExpect(status().isCreated()),
				"$.id")).longValue();
		long c6 = id(admin.post("/api/v1/academics/classes", "{\"name\":\"Class 6\"}"));
		long english = id(admin.post("/api/v1/academics/subjects", "{\"name\":\"English\"}"));
		long science = id(admin.post("/api/v1/academics/subjects", "{\"name\":\"Science\"}"));
		String url = "/api/v1/academics/years/" + year + "/classes/" + c6 + "/subjects";

		// QA-ACA-004: passing marks above maximum.
		admin.put(url, "[{\"subjectId\":" + science + ",\"maxMarks\":75,\"passingMarks\":80}]")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASSING_MARKS_INVALID"));

		// FR 9.4 example: English 100, Science 75.
		admin.put(url, "[{\"subjectId\":" + english + ",\"maxMarks\":100,\"passingMarks\":33},"
				+ "{\"subjectId\":" + science + ",\"maxMarks\":75,\"passingMarks\":25}]")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[1].subjectName").value("Science"))
			.andExpect(jsonPath("$[1].maxMarks").value(75.0));

		// A subject used by a class cannot be deleted.
		admin.delete("/api/v1/academics/subjects/" + science).andExpect(status().isConflict());

		// Removing it from the class keeps the row but deactivates it.
		admin.put(url, "[{\"subjectId\":" + english + ",\"maxMarks\":100}]")
			.andExpect(jsonPath("$[?(@.subjectName=='Science')].status").value("INACTIVE"));
	}
}
