package com.selectcode.sms.platform;

import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.ActorType;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in super admin (system owner, DECISIONS D-01a). Outside a school {@code activeSchoolId} is null; after
 * "Open school" it is set, and the super admin works in that school with every permission.
 */
public record PlatformPrincipal(long platformUserId, String email, String name, Long activeSchoolId,
		String activeSchoolCode, String activeSchoolName) implements ActorPrincipal, AuthenticatedPrincipal, Serializable {

	public PlatformPrincipal withActiveSchool(long id, String code, String schoolName) {
		return new PlatformPrincipal(platformUserId, email, name, id, code, schoolName);
	}

	public PlatformPrincipal withoutActiveSchool() {
		return new PlatformPrincipal(platformUserId, email, name, null, null, null);
	}

	@Override
	public String getName() {
		return "p" + platformUserId;
	}

	@Override
	public Long tenantSchoolId() {
		return activeSchoolId;
	}

	@Override
	public ActorType actorType() {
		return ActorType.PLATFORM;
	}

	@Override
	public long actorId() {
		return platformUserId;
	}

	@Override
	public String displayName() {
		return name;
	}

	@Override
	public String loginName() {
		return email;
	}

	@Override
	public String schoolCode() {
		return activeSchoolCode;
	}

	@Override
	public String schoolName() {
		return activeSchoolName;
	}
}
