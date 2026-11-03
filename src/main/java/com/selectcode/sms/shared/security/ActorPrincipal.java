package com.selectcode.sms.shared.security;

import com.selectcode.sms.shared.tenant.TenantPrincipal;

/** Common view of any signed-in principal (school user or super admin), stored in the server-side session. */
public interface ActorPrincipal extends TenantPrincipal {

	ActorType actorType();

	long actorId();

	String displayName();

	/** Login name shown in the UI: username for school users, email for the super admin. */
	String loginName();

	/** Code of the school the principal is working in, or {@code null}. */
	String schoolCode();

	/** Name of the school the principal is working in, or {@code null}. */
	String schoolName();
}
