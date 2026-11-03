package com.selectcode.sms.shared.attendance;

import java.time.LocalDate;
import java.util.List;

/** Students absent several working days in a row (FR 13.6), for the dashboard badge (FR 3.6) and class reports (FR 15.4). */
public interface RepeatedAbsenceCount {

	record RepeatedStudent(long studentId, String grNo, String name, long sectionId, String sectionLabel, int daysInARow,
			LocalDate since) {
	}

	int repeatedAbsenceCount(LocalDate asOf);

	List<RepeatedStudent> repeatedStudents(LocalDate asOf);
}
