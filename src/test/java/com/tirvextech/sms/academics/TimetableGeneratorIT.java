package com.tirvextech.sms.academics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The automatic timetable: every lesson placed, no teacher in two places, locked periods kept (FR 6.4). */
@AutoConfigureMockMvc
class TimetableGeneratorIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void generatesAClashFreeWeekForTheWholeSchool() throws Exception {
		var school = TestData.school("TTG");
		var db = TestDatabase.superuser();
		long year = TestData.yearAroundToday(school, true);
		for (int d = 1; d <= 7; d++) { // Monday–Thursday full, Friday half, weekend off
			db.update("insert into working_day (school_id, academic_year_id, weekday, day_type) values (?, ?, ?, ?)",
					school.id(), year, d, d <= 4 ? "FULL" : d == 5 ? "HALF" : "OFF");
		}
		long class6 = TestData.schoolClass(school, "Class 6", 6);
		long class7 = TestData.schoolClass(school, "Class 7", 7);
		long s6a = TestData.section(school, class6, "A");
		long s6b = TestData.section(school, class6, "B");
		long s7a = TestData.section(school, class7, "A");
		long maths = TestData.subject(school, "Mathematics");
		long english = TestData.subject(school, "English");
		long science = TestData.subject(school, "Science");
		long urdu = TestData.subject(school, "Urdu");
		long computer = TestData.subject(school, "Computer");
		long tMaths = TestData.teacher(school, "Maths Teacher");
		long tEnglish = TestData.teacher(school, "English Teacher");
		long tScience = TestData.teacher(school, "Science Teacher");
		long tScience7 = TestData.teacher(school, "Science Seven");
		long tUrdu = TestData.teacher(school, "Urdu Teacher");
		long tComputer = TestData.teacher(school, "Computer Teacher");
		TestData.admin(school, "admin", "admin-password-1");
		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		String base = "/api/v1/academics/years/" + year;

		// Without a bell schedule there is nothing to fill
		admin.post(base + "/timetable/generate", "{}").andExpect(jsonPath("$.code").value("BELL_SCHEDULE_REQUIRED"));

		// 6 periods (3, break, 3); only the first 4 happen on half days
		admin.put(base + "/bell-schedule", """
				[{"kind":"PERIOD","startTime":"08:00","endTime":"08:40"},
				 {"kind":"PERIOD","startTime":"08:40","endTime":"09:20"},
				 {"kind":"PERIOD","startTime":"09:20","endTime":"10:00"},
				 {"kind":"BREAK","label":"Lunch","startTime":"10:00","endTime":"10:30"},
				 {"kind":"PERIOD","startTime":"10:30","endTime":"11:10"},
				 {"kind":"PERIOD","startTime":"11:10","endTime":"11:50","onHalfDay":false},
				 {"kind":"PERIOD","startTime":"11:50","endTime":"12:30","onHalfDay":false}]""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[4].periodNo").value(4))
			.andExpect(jsonPath("$[3].periodNo").value(Matchers.nullValue()));
		// Overlapping rows are refused
		admin.put(base + "/bell-schedule", """
				[{"kind":"PERIOD","startTime":"08:00","endTime":"08:40"},{"kind":"PERIOD","startTime":"08:30","endTime":"09:00"}]""")
			.andExpect(jsonPath("$.code").value("BELL_OVERLAP"));

		// Periods a week: 6 + 6 + 4 (doubles) + 5 + 2 (double) = 23 of 28 slots (4 × 6 + 4 on Friday)
		String subjects = """
				[{"subjectId":%d,"maxMarks":100,"periodsPerWeek":6},{"subjectId":%d,"maxMarks":100,"periodsPerWeek":6},
				 {"subjectId":%d,"maxMarks":100,"periodsPerWeek":4,"doublePeriod":true},
				 {"subjectId":%d,"maxMarks":100,"periodsPerWeek":5},
				 {"subjectId":%d,"maxMarks":50,"periodsPerWeek":2,"doublePeriod":true}]"""
			.formatted(maths, english, science, urdu, computer);
		admin.put(base + "/classes/" + class6 + "/subjects", subjects).andExpect(status().isOk())
			.andExpect(jsonPath("$[2].periodsPerWeek").value(4)).andExpect(jsonPath("$[2].doublePeriod").value(true));
		admin.put(base + "/classes/" + class7 + "/subjects", subjects).andExpect(status().isOk());

		// Shared teachers: Maths, English, Urdu and Computer teach all three sections
		for (long section : List.of(s6a, s6b, s7a)) {
			assign(admin, base, section, maths, tMaths);
			assign(admin, base, section, english, tEnglish);
			assign(admin, base, section, urdu, tUrdu);
			assign(admin, base, section, computer, tComputer);
			assign(admin, base, section, science, section == s7a ? tScience7 : tScience);
			assign(admin, base, section, null, tEnglish); // class teacher
		}

		// A locked period in 6-A is kept: Urdu on Monday, period 6
		admin.put(base + "/sections/" + s6a + "/timetable",
				"[{\"weekday\":1,\"periodNo\":6,\"subjectId\":%d,\"staffId\":%d,\"locked\":true}]".formatted(urdu, tUrdu))
			.andExpect(status().isOk());

		var proposal = admin.post(base + "/timetable/generate", "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.total").value(23 * 3 - 1))
			.andExpect(jsonPath("$.placed").value(23 * 3 - 1))
			.andExpect(jsonPath("$.sections.length()").value(3));
		String json = proposal.andReturn().getResponse().getContentAsString();

		// Save it as proposed
		List<Map<String, Object>> sections = ApiClient.json(proposal, "$.sections");
		StringBuilder body = new StringBuilder("[");
		for (var s : sections) {
			body.append(body.length() > 1 ? "," : "").append("{\"sectionId\":").append(s.get("sectionId"))
				.append(",\"periods\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(s.get("periods")))
				.append('}');
		}
		admin.put(base + "/timetable", body.append(']').toString()).andExpect(status().isOk())
			.andExpect(jsonPath("$.periods").value(23 * 3));

		// No teacher anywhere twice in one period; every section has its lessons; the lock is still there
		assertThat(db.queryForObject("""
				select count(*) from (select staff_id, weekday, period_no from timetable_period
				where academic_year_id = ? and staff_id is not null group by 1, 2, 3 having count(*) > 1) x""", Long.class, year))
			.isZero();
		for (long section : List.of(s6a, s6b, s7a)) {
			assertThat(db.queryForObject("select count(*) from timetable_period where section_id = ? and subject_id = ?",
					Long.class, section, maths)).isEqualTo(6);
			assertThat(db.queryForObject("select count(*) from timetable_period where section_id = ? and subject_id = ?",
					Long.class, section, science)).isEqualTo(4);
		}
		assertThat(db.queryForObject("""
				select count(*) from timetable_period where section_id = ? and weekday = 1 and period_no = 6
				  and subject_id = ? and locked""", Long.class, s6a, urdu)).isEqualTo(1);
		// Half day: nothing after period 4 on Friday; doubles are back to back and never across the break
		assertThat(db.queryForObject("select count(*) from timetable_period where academic_year_id = ? and weekday = 5 and period_no > 4",
				Long.class, year)).isZero();
		assertThat(db.queryForObject("""
				select count(*) from timetable_period t where t.academic_year_id = ? and t.subject_id = ?
				  and not exists (select 1 from timetable_period o where o.section_id = t.section_id and o.weekday = t.weekday
				                  and o.subject_id = t.subject_id and abs(o.period_no - t.period_no) = 1
				                  and not (least(o.period_no, t.period_no) = 3))""", Long.class, year, science)).isZero();
		assertThat(json).doesNotContain("Could not fit");

		// Generating only 7-A keeps 6-A and 6-B as they are and works around their teachers
		admin.post(base + "/timetable/generate", "{\"sectionIds\":[%d]}".formatted(s7a))
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.sections.length()").value(1));

		// An overloaded teacher is reported by name
		long class8 = TestData.schoolClass(school, "Class 8", 8);
		long s8a = TestData.section(school, class8, "A");
		admin.put(base + "/classes/" + class8 + "/subjects",
				"[{\"subjectId\":%d,\"maxMarks\":100,\"periodsPerWeek\":20}]".formatted(maths)).andExpect(status().isOk());
		assign(admin, base, s8a, maths, tMaths);
		admin.post(base + "/timetable/generate", "{\"sectionIds\":[%d]}".formatted(s8a))
			.andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.problems[*]").value(Matchers.hasItem(Matchers.containsString("Maths Teacher has"))));
	}

	private static void assign(ApiClient admin, String base, long section, Long subject, long staff) throws Exception {
		admin.post(base + "/assignments", "{\"sectionId\":%d,%s\"staffId\":%d}".formatted(section,
				subject == null ? "" : "\"subjectId\":" + subject + ",", staff)).andExpect(status().isCreated());
	}
}
