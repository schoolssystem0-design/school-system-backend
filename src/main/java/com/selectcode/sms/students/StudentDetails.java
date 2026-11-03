package com.selectcode.sms.students;

import java.time.LocalDate;

/** A student's personal details (FR 4.7, 5.1). */
public record StudentDetails(String name, String gender, LocalDate dob, String placeOfBirth, String bFormNo,
			String religion, String nationality, String phone, String address, String previousSchool,
			String studyGroup, String seatNo, Long photoFileId, String notes) {
}
