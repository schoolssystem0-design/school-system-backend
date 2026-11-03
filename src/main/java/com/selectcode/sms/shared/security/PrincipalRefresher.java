package com.selectcode.sms.shared.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

/**
 * Re-checks a session principal on every request (account still active, school not suspended) and returns its
 * current authorities. This makes deactivation, suspension and role changes take effect immediately. Implemented by
 * the iam module (school users) and the platform module (super admin).
 */
public interface PrincipalRefresher {

	record Refreshed(boolean valid, Collection<? extends GrantedAuthority> authorities, boolean mustChangePassword) {

		public static Refreshed invalid() {
			return new Refreshed(false, java.util.List.of(), false);
		}
	}

	boolean supports(ActorPrincipal principal);

	/** Called with the principal's school already in TenantContext. */
	Refreshed refresh(ActorPrincipal principal);
}
