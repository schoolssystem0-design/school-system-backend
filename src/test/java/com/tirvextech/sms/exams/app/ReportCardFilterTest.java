package com.tirvextech.sms.exams.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tirvextech.sms.exams.app.ExamDocuments.ReportCardFilter;
import com.tirvextech.sms.exams.app.ResultService.PaperResult;
import com.tirvextech.sms.exams.app.ResultService.StudentResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportCardFilterTest {

	private static StudentResult student(long id, int position, String percent, Boolean passed) {
		return new StudentResult(id, "GR", "Name", 1L, "A", List.<PaperResult>of(), "80", "100", percent, "A", "", passed,
				position, 0, null, null, null);
	}

	@Test
	void topPassedAndPercentFiltersCombine() {
		var students = List.of(student(1, 1, "95", true), student(2, 2, "70", true), student(3, 3, "40", false));
		assertThat(ExamDocuments.pickStudents(students, new ReportCardFilter(null, null, 1, null, null)))
			.extracting(StudentResult::studentId).containsExactly(1L);
		assertThat(ExamDocuments.pickStudents(students, new ReportCardFilter(null, null, null, true, null)))
			.hasSize(2);
		assertThat(ExamDocuments.pickStudents(students, new ReportCardFilter(null, List.of(2L, 3L), null, null, null)))
			.extracting(StudentResult::studentId).containsExactly(2L, 3L);
		assertThat(ExamDocuments.pickStudents(students, new ReportCardFilter(null, null, null, null, "80")))
			.extracting(StudentResult::studentId).containsExactly(1L);
	}
}