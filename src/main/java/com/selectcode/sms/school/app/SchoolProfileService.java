package com.selectcode.sms.school.app;

import com.selectcode.sms.school.infra.SchoolProfileStore;
import com.selectcode.sms.school.infra.SchoolProfileStore.Profile;
import com.selectcode.sms.school.infra.SchoolProfileStore.Signature;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.files.FilePurpose;
import com.selectcode.sms.shared.files.FileService;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * School profile and document identity (FR 1.1): the details printed on every voucher, receipt, report card and
 * certificate. One change updates all future documents.
 */
@Service
public class SchoolProfileService {

	private final SchoolProfileStore store;

	private final FileService files;

	private final AuditService audit;

	SchoolProfileService(SchoolProfileStore store, FileService files, AuditService audit) {
		this.store = store;
		this.files = files;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public Profile get() {
		return store.find().orElseThrow(() -> ApiException.notFound("School profile"));
	}

	@Transactional
	public Profile update(Profile input) {
		Profile before = get();
		if (input.name() == null || input.name().isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", "School name is required");
		}
		checkFile(input.logoFileId(), FilePurpose.SCHOOL_LOGO);
		checkFile(input.letterheadFileId(), FilePurpose.SCHOOL_LETTERHEAD);
		checkFile(input.stampFileId(), FilePurpose.SCHOOL_STAMP);
		List<Signature> signatures = input.signatures() == null ? List.of() : input.signatures();
		if (signatures.size() > 10) {
			throw ApiException.businessRule("TOO_MANY_SIGNATURES", "At most 10 signatures");
		}
		for (Signature signature : signatures) {
			if (signature.role() == null || signature.role().isBlank() || signature.name() == null
					|| signature.name().isBlank()) {
				throw ApiException.businessRule("SIGNATURE_INCOMPLETE", "Each signature needs a role and a name");
			}
			checkFile(signature.fileId(), FilePurpose.SIGNATURE);
		}
		Profile cleaned = new Profile(input.name().trim(), trim(input.campusName()), trim(input.address()),
				trim(input.phonePrimary()), trim(input.phoneSecondary()), trim(input.email()), trim(input.headName()),
				trim(input.headTitle()), input.logoFileId(), input.letterheadFileId(), input.stampFileId(), signatures,
				input.version());
		if (!store.update(cleaned)) {
			throw ApiException.conflict("CONFLICT_VERSION", "The profile was changed by someone else. Reload and try again.");
		}
		Profile after = get();
		audit.record("SCHOOL_PROFILE_UPDATED", "school_profile", null, before, after);
		return after;
	}

	private void checkFile(Long fileId, FilePurpose purpose) {
		if (fileId == null) {
			return;
		}
		var file = files.find(fileId).orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "File not found"));
		if (!file.purpose().equals(purpose.name())) {
			throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "This file was not uploaded as " + purpose.name());
		}
	}

	private static String trim(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
