package com.tirvextech.sms.platform;

import com.tirvextech.sms.platform.domain.School;
import com.tirvextech.sms.platform.infra.SchoolRepository;
import com.tirvextech.sms.shared.tenant.SchoolLookup;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The platform's school registry, exposed to other modules through {@link SchoolLookup}. */
@Service
public class SchoolDirectory implements SchoolLookup {

	private final SchoolRepository schools;

	private final com.tirvextech.sms.platform.app.SubscriptionService billing;

	SchoolDirectory(SchoolRepository schools, com.tirvextech.sms.platform.app.SubscriptionService billing) {
		this.schools = schools;
		this.billing = billing;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SchoolRef> findByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		return schools.findByCode(code.trim().toUpperCase(Locale.ROOT)).map(this::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SchoolRef> findById(long id) {
		return schools.findById(id).map(this::ref);
	}

	private SchoolRef ref(School school) {
		return new SchoolRef(school.getId(), school.getCode(), school.getName(),
				school.getStatus() == School.Status.ACTIVE, school.getTrialEndsAt(), billing.paused(school.getId()));
	}
}
