package com.selectcode.sms.shared.pdf;

import java.time.LocalDate;
import java.util.List;

/** A one-page certificate: letterhead, serial and date, title, body paragraphs, stamp and signatures. */
public final class CertificatePdf {

	public record CertificateContent(String title, String serialNo, LocalDate issueDate, List<String> paragraphs,
			List<String> signatureRoles) {
	}

	private CertificatePdf() {
	}

	public static byte[] render(Letterhead letterhead, CertificateContent content) {
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, letterhead);
			PdfKit.line(doc, "Serial No. " + content.serialNo(), "Date: " + PdfKit.date(content.issueDate()));
			PdfKit.title(doc, content.title());
			for (String paragraph : content.paragraphs()) {
				PdfKit.paragraph(doc, paragraph);
			}
			PdfKit.signatures(doc, letterhead, content.signatureRoles(), true);
		});
	}
}
