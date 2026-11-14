package com.selectcode.sms.shared.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Feeds the current school to Hibernate's {@code @TenantId} discriminator, so every JPA query on a
 * {@link TenantScopedEntity} is filtered by school and every insert is stamped with it.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

	/** Matches no school: tenant entities are invisible when no tenant is set. */
	static final Long NO_SCHOOL = -1L;

	/** Root tenant: disables the filter. Only used across all schools (super admin overview). */
	static final Long ALL_SCHOOLS = 0L;

	@Override
	public Long resolveCurrentTenantIdentifier() {
		if (TenantContext.isAllSchools()) {
			return ALL_SCHOOLS;
		}
		return TenantContext.schoolId().orElse(NO_SCHOOL);
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}

	@Override
	public boolean isRoot(Long tenantId) {
		return ALL_SCHOOLS.equals(tenantId);
	}
}
