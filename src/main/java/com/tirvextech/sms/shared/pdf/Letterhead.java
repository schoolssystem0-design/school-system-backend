package com.tirvextech.sms.shared.pdf;

import java.awt.Color;
import java.util.List;
import java.util.Optional;

/** The school's document identity (FR 1.1) as printed on every document. Images are raw PNG/JPEG bytes or null. */
public record Letterhead(String schoolName, String campusName, String address, String phones, String email,
		String headName, String headTitle, byte[] logo, byte[] stamp, List<SignatureImage> signatures, DocStyle style) {

	public Letterhead {
		style = style == null ? DocStyle.DEFAULT : style;
	}

	/** A letterhead with the default document style. */
	public Letterhead(String schoolName, String campusName, String address, String phones, String email, String headName,
			String headTitle, byte[] logo, byte[] stamp, List<SignatureImage> signatures) {
		this(schoolName, campusName, address, phones, email, headName, headTitle, logo, stamp, signatures, DocStyle.DEFAULT);
	}

	public record SignatureImage(String role, String name, String title, byte[] image) {
	}

	/**
	 * How the school's documents look: the theme colour, an optional footer line (motto, website) and a faint logo
	 * watermark behind the page.
	 */
	public record DocStyle(Color accent, String footer, boolean watermark) {

		public static final DocStyle DEFAULT = new DocStyle(PdfKit.colour(null), null, false);

		public DocStyle {
			accent = accent == null ? PdfKit.colour(null) : accent;
		}
	}

	/** The signature configured for a role (e.g. "Principal"), matched case-insensitively. */
	public Optional<SignatureImage> signature(String role) {
		return signatures.stream().filter(s -> s.role() != null && s.role().equalsIgnoreCase(role)).findFirst();
	}
}
