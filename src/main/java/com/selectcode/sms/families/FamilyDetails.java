package com.selectcode.sms.families;

/**
 * A family's details as entered on the family/admission form (FR 8.1). {@code messageLanguage} is "EN" or "UR" (Roman
 * Urdu); null keeps the family's current language.
 */
public record FamilyDetails(String fatherName, String fatherCnic, String fatherPhone, String fatherProfession,
			String motherName, String motherCnic, String motherPhone, String email, String address,
			String emergencyName, String emergencyPhone, String preferredChannel, String remarks, String messageLanguage) {

	/** Forms that do not ask for the message language. */
	public FamilyDetails(String fatherName, String fatherCnic, String fatherPhone, String fatherProfession,
			String motherName, String motherCnic, String motherPhone, String email, String address,
			String emergencyName, String emergencyPhone, String preferredChannel, String remarks) {
		this(fatherName, fatherCnic, fatherPhone, fatherProfession, motherName, motherCnic, motherPhone, email, address,
				emergencyName, emergencyPhone, preferredChannel, remarks, null);
	}
}
