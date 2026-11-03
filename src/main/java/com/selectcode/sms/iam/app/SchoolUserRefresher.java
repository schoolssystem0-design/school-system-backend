package com.selectcode.sms.iam.app;

import com.selectcode.sms.iam.SchoolUserPrincipal;
import com.selectcode.sms.iam.infra.RoleLinks;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.PrincipalRefresher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per request: the user must still be active and the school not suspended; authorities are the user's current
 * permissions. A role change or deactivation therefore applies on the user's next click.
 */
@Component
class SchoolUserRefresher implements PrincipalRefresher {

	static final String SCHOOL_USER_AUTHORITY = "ROLE_SCHOOL_USER";

	private final JdbcTemplate jdbc;

	private final RoleLinks roleLinks;

	SchoolUserRefresher(JdbcTemplate jdbc, RoleLinks roleLinks) {
		this.jdbc = jdbc;
		this.roleLinks = roleLinks;
	}

	@Override
	public boolean supports(ActorPrincipal principal) {
		return principal instanceof SchoolUserPrincipal;
	}

	@Override
	@Transactional(readOnly = true)
	public Refreshed refresh(ActorPrincipal principal) {
		SchoolUserPrincipal user = (SchoolUserPrincipal) principal;
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select u.status as user_status, u.must_change_password, u.data_scope, s.status as school_status
				from app_user u join school s on s.id = u.school_id
				where u.id = ? and u.school_id = ?""", user.userId(), user.schoolId());
		if (rows.isEmpty()) {
			return Refreshed.invalid();
		}
		Map<String, Object> row = rows.getFirst();
		if (!"ACTIVE".equals(row.get("user_status")) || !"ACTIVE".equals(row.get("school_status"))) {
			return Refreshed.invalid();
		}
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority(SCHOOL_USER_AUTHORITY));
		authorities.add(new SimpleGrantedAuthority(
				com.selectcode.sms.shared.security.DataScope.valueOf((String) row.get("data_scope")).authority()));
		for (String permission : roleLinks.effectivePermissions(user.userId())) {
			authorities.add(new SimpleGrantedAuthority(permission));
		}
		return new Refreshed(true, authorities, Boolean.TRUE.equals(row.get("must_change_password")));
	}
}
