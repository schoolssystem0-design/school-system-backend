package com.selectcode.sms.shared.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the principal of the current request. */
public final class CurrentActor {

	public static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

	private CurrentActor() {
	}

	public static Optional<ActorPrincipal> get() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof ActorPrincipal principal) {
			return Optional.of(principal);
		}
		return Optional.empty();
	}

	public static ActorPrincipal require() {
		return get().orElseThrow(() -> new IllegalStateException("No signed-in actor"));
	}

	public static boolean isSuperAdmin() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null) {
			return false;
		}
		for (GrantedAuthority authority : auth.getAuthorities()) {
			if (SUPER_ADMIN_AUTHORITY.equals(authority.getAuthority())) {
				return true;
			}
		}
		return false;
	}

	public static boolean hasPermission(String permission) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
	}
}
