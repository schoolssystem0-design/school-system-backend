package com.selectcode.sms.shared.tenant;

import java.util.Optional;

/**
 * Read access to the platform's school registry for modules that must not depend on the platform module
 * (avoids a platform ↔ iam cycle). Implemented by the platform module.
 */
public interface SchoolLookup {

	record SchoolRef(long id, String code, String name, boolean active) {
	}

	Optional<SchoolRef> findByCode(String code);

	Optional<SchoolRef> findById(long id);
}
