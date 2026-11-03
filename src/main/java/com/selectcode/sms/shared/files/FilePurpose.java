package com.selectcode.sms.shared.files;

import com.selectcode.sms.shared.security.Permissions;
import java.util.Set;

/**
 * What a file is for: decides allowed types, maximum size, who may upload it and who may download it
 * (SECURITY.md §4, FR 5.11). An empty download set means any signed-in user of the school.
 */
public enum FilePurpose {

	SCHOOL_LOGO(FileKind.IMAGE, Set.of(Permissions.SCHOOL_PROFILE_EDIT), Set.of()),
	SCHOOL_LETTERHEAD(FileKind.IMAGE, Set.of(Permissions.SCHOOL_PROFILE_EDIT), Set.of()),
	SCHOOL_STAMP(FileKind.IMAGE, Set.of(Permissions.SCHOOL_PROFILE_EDIT), Set.of()),
	SIGNATURE(FileKind.IMAGE, Set.of(Permissions.SCHOOL_PROFILE_EDIT), Set.of()),
	/** Student or staff photo (ID cards, profiles). */
	PHOTO(FileKind.IMAGE, Set.of(Permissions.STUDENTS_MANAGE, Permissions.ADMISSIONS_ENROL, Permissions.STAFF_MANAGE),
			Set.of(Permissions.STUDENTS_VIEW, Permissions.STAFF_VIEW)),
	/** B-Form, birth certificate, previous school certificate… (FR 4.9): private. */
	STUDENT_DOCUMENT(FileKind.DOCUMENT, Set.of(Permissions.STUDENTS_MANAGE, Permissions.ADMISSIONS_ENROL),
			Set.of(Permissions.STUDENTS_PRIVATE_VIEW)),
	/** Degree copies, CNIC copy… (FR 6.1). */
	STAFF_DOCUMENT(FileKind.DOCUMENT, Set.of(Permissions.STAFF_MANAGE), Set.of(Permissions.STAFF_MANAGE)),
	/** Bills and receipts attached to income and expense entries (FR 11.5). */
	ACCOUNT_DOCUMENT(FileKind.DOCUMENT, Set.of(Permissions.ACCOUNTS_ENTRY_ADD), Set.of(Permissions.ACCOUNTS_VIEW));

	public enum FileKind {

		IMAGE(5L * 1024 * 1024, Set.of("image/png", "image/jpeg", "image/webp")),
		DOCUMENT(10L * 1024 * 1024, Set.of("application/pdf", "image/png", "image/jpeg"));

		final long maxBytes;

		final Set<String> allowedTypes;

		FileKind(long maxBytes, Set<String> allowedTypes) {
			this.maxBytes = maxBytes;
			this.allowedTypes = allowedTypes;
		}
	}

	final FileKind kind;

	/** Any one of these permits upload. */
	final Set<String> uploadPermissions;

	/** Any one of these permits download; empty = any signed-in user of the school. */
	final Set<String> viewPermissions;

	FilePurpose(FileKind kind, Set<String> uploadPermissions, Set<String> viewPermissions) {
		this.kind = kind;
		this.uploadPermissions = uploadPermissions;
		this.viewPermissions = viewPermissions;
	}
}
