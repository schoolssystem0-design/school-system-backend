package com.tirvextech.sms.school.app;

import com.tirvextech.sms.school.infra.SchoolProfileStore;
import com.tirvextech.sms.shared.files.FileService;
import com.tirvextech.sms.shared.pdf.Letterhead;
import com.tirvextech.sms.shared.pdf.LetterheadSource;
import com.tirvextech.sms.shared.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The current school's letterhead for PDFs, from the school profile and document identity (FR 1.1). */
@Component
class SchoolLetterhead implements LetterheadSource {

	private final SchoolProfileStore profiles;

	private final FileService files;

	SchoolLetterhead(SchoolProfileStore profiles, FileService files) {
		this.profiles = profiles;
		this.files = files;
	}

	@Override
	@Transactional(readOnly = true)
	public Letterhead current() {
		var p = profiles.find().orElseThrow(() -> ApiException.notFound("School profile"));
		String phones = p.phoneSecondary() == null ? p.phonePrimary()
				: (p.phonePrimary() == null ? p.phoneSecondary() : p.phonePrimary() + ", " + p.phoneSecondary());
		var signatures = p.signatures()
			.stream()
			.map(s -> new Letterhead.SignatureImage(s.role(), s.name(), s.title(), files.content(s.fileId()).orElse(null)))
			.toList();
		return new Letterhead(p.name(), p.campusName(), p.address(), phones, p.email(), p.headName(), p.headTitle(),
				files.content(p.logoFileId()).orElse(null), files.content(p.stampFileId()).orElse(null), signatures);
	}
}
