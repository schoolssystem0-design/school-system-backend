package com.selectcode.sms.families;

/** A family's details as entered on the family/admission form (FR 8.1). */
public record FamilyDetails(String fatherName, String fatherCnic, String fatherPhone, String fatherProfession,
			String motherName, String motherCnic, String motherPhone, String email, String address,
			String emergencyName, String emergencyPhone, String preferredChannel, String remarks) {
}
