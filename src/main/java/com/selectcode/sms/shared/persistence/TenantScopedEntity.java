package com.selectcode.sms.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every school-owned entity. {@code school_id} is filled from the current tenant on insert and added
 * to every query by Hibernate; PostgreSQL row-level security enforces the same rule underneath.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

	@TenantId
	@Column(name = "school_id", nullable = false, updatable = false)
	private Long schoolId;

	public Long getSchoolId() {
		return schoolId;
	}
}
