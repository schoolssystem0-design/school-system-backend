package com.selectcode.sms.shared.tenant;

/** An authenticated principal that belongs to, or is currently working in, at most one school. */
public interface TenantPrincipal {

	/** The school this principal's requests run as, or {@code null} (super admin outside any school). */
	Long tenantSchoolId();
}
