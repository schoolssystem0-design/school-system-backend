package com.selectcode.sms.shared.tenant;

import java.util.Optional;

/**
 * Read access to the platform's school registry for modules that must not depend on the platform module
 * (avoids a platform ↔ iam cycle). Implemented by the platform module.
 */
public interface SchoolLookup {

	/** {@code trialEndsAt}: end of the free trial, or null for full access. */
	record SchoolRef(long id, String code, String name, boolean active, java.time.Instant trialEndsAt) {

		public boolean trialEnded(java.time.Instant now) {
			return trialEndsAt != null && !now.isBefore(trialEndsAt);
		}
	}

	Optional<SchoolRef> findByCode(String code);

	Optional<SchoolRef> findById(long id);
}
