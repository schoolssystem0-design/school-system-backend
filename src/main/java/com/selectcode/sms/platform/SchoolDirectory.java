package com.selectcode.sms.platform;

import com.selectcode.sms.platform.domain.School;
import com.selectcode.sms.platform.infra.SchoolRepository;
import com.selectcode.sms.shared.tenant.SchoolLookup;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The platform's school registry, exposed to other modules through {@link SchoolLookup}. */
@Service
public class SchoolDirectory implements SchoolLookup {

	private final SchoolRepository schools;

	SchoolDirectory(SchoolRepository schools) {
		this.schools = schools;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SchoolRef> findByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		return schools.findByCode(code.trim().toUpperCase(Locale.ROOT)).map(SchoolDirectory::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SchoolRef> findById(long id) {
		return schools.findById(id).map(SchoolDirectory::ref);
	}

	private static SchoolRef ref(School school) {
		return new SchoolRef(school.getId(), school.getCode(), school.getName(),
				school.getStatus() == School.Status.ACTIVE);
	}
}
