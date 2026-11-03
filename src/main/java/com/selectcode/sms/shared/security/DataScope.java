package com.selectcode.sms.shared.security;

/**
 * Which records a school user may see (FR 2.5): all, only their assigned classes (teachers), or only their own.
 * Carried as an authority {@code SCOPE_<value>} on each request; the super admin always has {@link #ALL}.
 */
public enum DataScope {

	ALL, ASSIGNED_CLASSES, SELF;

	public static final String AUTHORITY_PREFIX = "SCOPE_";

	public String authority() {
		return AUTHORITY_PREFIX + name();
	}

	public static DataScope current() {
		if (CurrentActor.isSuperAdmin()) {
			return ALL;
		}
		for (DataScope scope : values()) {
			if (CurrentActor.hasPermission(scope.authority())) {
				return scope;
			}
		}
		// Missing scope is a programming error: fail closed (most restrictive), never open.
		return SELF;
	}
}
