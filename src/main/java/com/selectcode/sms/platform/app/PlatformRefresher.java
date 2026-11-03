package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.PlatformPrincipal;
import com.selectcode.sms.platform.domain.PlatformUser;
import com.selectcode.sms.platform.infra.PlatformUserRepository;
import com.selectcode.sms.platform.infra.SchoolRepository;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.PrincipalRefresher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per request for the super admin: account still active; inside a school, every permission (full access to all
 * schools' data, DECISIONS D-01a). Suspended schools can still be opened by the super admin.
 */
@Component
class PlatformRefresher implements PrincipalRefresher {

	private static final List<GrantedAuthority> ALL_PERMISSIONS = Permissions.CODES.stream()
		.map(code -> (GrantedAuthority) new SimpleGrantedAuthority(code))
		.toList();

	private final PlatformUserRepository users;

	private final SchoolRepository schools;

	PlatformRefresher(PlatformUserRepository users, SchoolRepository schools) {
		this.users = users;
		this.schools = schools;
	}

	@Override
	public boolean supports(ActorPrincipal principal) {
		return principal instanceof PlatformPrincipal;
	}

	@Override
	@Transactional(readOnly = true)
	public Refreshed refresh(ActorPrincipal principal) {
		PlatformPrincipal admin = (PlatformPrincipal) principal;
		boolean active = users.findById(admin.platformUserId()).map(PlatformUser::isActive).orElse(false);
		if (!active) {
			return Refreshed.invalid();
		}
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority(CurrentActor.SUPER_ADMIN_AUTHORITY));
		if (admin.activeSchoolId() != null) {
			if (!schools.existsById(admin.activeSchoolId())) {
				return Refreshed.invalid();
			}
			authorities.addAll(ALL_PERMISSIONS);
		}
		return new Refreshed(true, authorities, false);
	}
}
