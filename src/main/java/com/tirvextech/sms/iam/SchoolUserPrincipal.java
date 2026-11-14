package com.selectcode.sms.iam;

import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.ActorType;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in school user, stored in the server-side session. Permissions and account status are not stored here;
 * they are re-read on every request. {@link #getName()} is unique across schools so Spring Session can find (and
 * revoke) all sessions of one user.
 */
public record SchoolUserPrincipal(long userId, long schoolId, String schoolCode, String schoolName, String username,
		String displayName) implements ActorPrincipal, AuthenticatedPrincipal, Serializable {

	public static String sessionName(long schoolId, long userId) {
		return "s" + schoolId + ":u" + userId;
	}

	@Override
	public String getName() {
		return sessionName(schoolId, userId);
	}

	@Override
	public Long tenantSchoolId() {
		return schoolId;
	}

	@Override
	public ActorType actorType() {
		return ActorType.USER;
	}

	@Override
	public long actorId() {
		return userId;
	}

	@Override
	public String loginName() {
		return username;
	}
}
