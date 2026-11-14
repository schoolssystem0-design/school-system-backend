package com.tirvextech.sms.academics;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The weekly timetable: what is checked when it is saved, and what a hand-over may not do (FR 6.4, 6.6). */
@AutoConfigureMockMvc
class TimetableIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void savingChecksTimesSubjectsTeachersAndClashes() throws Exception {
		var school = TestData.school("TTB");
		long year = TestData.yearAroundToday(school, true);
		long class6 = TestData.schoolClass(school, "Class 6", 6);
		long secA = TestData.section(school, class6, "A");
		long secB = TestData.section(school, class6, "B");
		long maths = TestData.subject(school, "Mathematics");
		long art = TestData.subject(school, "Art"); // not taught in Class 6
		TestData.classSubject(school, year, class6, maths);
		long rabia = TestData.teacher(school, "Rabia Noor");
		long bilal = TestData.teacher(school, "Bilal Ahmed");
		TestData.admin(school, "admin", "admin-password-1");
		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		String base = "/api/v1/academics/years/" + year;

		// A free period keeps its bell times; a lesson is saved with its times
		admin.put(base + "/sections/" + secA + "/timetable", """
				[{"weekday":1,"periodNo":1,"startTime":"08:00:00","endTime":"08:40:00","subjectId":%d,"staffId":%d},
				 {"weekday":1,"periodNo":2,"startTime":"08:40:00","endTime":"09:20:00"}]""".formatted(maths, bilal))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.periodNo == 2)].startTime").value(Matchers.hasItem("08:40:00")));

		// A period cannot end before it starts
		admin.put(base + "/sections/" + secB + "/timetable",
				"[{\"weekday\":2,\"periodNo\":1,\"startTime\":\"10:00:00\",\"endTime\":\"09:00:00\"}]")
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("PERIOD_TIME_INVALID"));
		// Only subjects of the class, only known teachers
		admin.put(base + "/sections/" + secB + "/timetable", "[{\"weekday\":2,\"periodNo\":1,\"subjectId\":%d}]".formatted(art))
			.andExpect(jsonPath("$.code").value("SUBJECT_NOT_IN_CLASS"));
		admin.put(base + "/sections/" + secB + "/timetable", "[{\"weekday\":2,\"periodNo\":1,\"staffId\":999999}]")
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
		// The clash names the other section and the day
		admin.put(base + "/sections/" + secB + "/timetable",
				"[{\"weekday\":1,\"periodNo\":1,\"subjectId\":%d,\"staffId\":%d}]".formatted(maths, bilal))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Bilal Ahmed already teaches Class 6 - A on Monday, period 1"));

		// Hand-over may not put the new teacher in two sections at once
		admin.put(base + "/sections/" + secB + "/timetable",
				"[{\"weekday\":1,\"periodNo\":1,\"subjectId\":%d,\"staffId\":%d}]".formatted(maths, rabia))
			.andExpect(status().isOk());
		admin.post(base + "/assignments/handover", "{\"fromStaffId\":%d,\"toStaffId\":%d,\"effectiveFrom\":\"%s\"}"
			.formatted(rabia, bilal, LocalDate.now()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(Matchers.containsString("Class 6 - A, Monday period 1")));
		admin.get(base + "/timetable?staffId=" + rabia).andExpect(jsonPath("$.length()").value(1));
	}
}
