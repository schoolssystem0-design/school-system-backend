package com.selectcode.sms.shared.pdf;

import java.util.List;
import java.util.Optional;

/** The school's document identity (FR 1.1) as printed on every document. Images are raw PNG/JPEG bytes or null. */
public record Letterhead(String schoolName, String campusName, String address, String phones, String email,
		String headName, String headTitle, byte[] logo, byte[] stamp, List<SignatureImage> signatures) {

	public record SignatureImage(String role, String name, String title, byte[] image) {
	}

	/** The signature configured for a role (e.g. "Principal"), matched case-insensitively. */
	public Optional<SignatureImage> signature(String role) {
		return signatures.stream().filter(s -> s.role() != null && s.role().equalsIgnoreCase(role)).findFirst();
	}
}
