package com.selectcode.sms.iam.infra;

import com.selectcode.sms.iam.domain.RoleTemplates;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The link tables {@code role_permission} and {@code user_role}. Plain JDBC because every row also carries
 * {@code school_id} (composite foreign keys), which JPA join tables cannot fill. Row-level security still applies.
 */
@Repository
public class RoleLinks {

	private final JdbcTemplate jdbc;

	RoleLinks(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Set<String> permissionsOfRole(long roleId) {
		return new HashSet<>(
				jdbc.queryForList("select permission from role_permission where role_id = ?", String.class, roleId));
	}

	public Map<Long, Set<String>> permissionsByRole() {
		Map<Long, Set<String>> result = new java.util.HashMap<>();
		jdbc.query("select role_id, permission from role_permission", rs -> {
			result.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
		});
		return result;
	}

	public void replaceRolePermissions(long roleId, Collection<String> permissions) {
		jdbc.update("delete from role_permission where role_id = ?", roleId);
		addRolePermissions(roleId, permissions);
	}

	public void addRolePermissions(long roleId, Collection<String> permissions) {
		long schoolId = TenantContext.requireSchoolId();
		for (String permission : permissions) {
			jdbc.update("""
					insert into role_permission (school_id, role_id, permission) values (?, ?, ?)
					on conflict do nothing""", schoolId, roleId, permission);
		}
	}

	public void addRolePermissionsForSchool(long schoolId, long roleId, Collection<String> permissions) {
		for (String permission : permissions) {
			jdbc.update("""
					insert into role_permission (school_id, role_id, permission) values (?, ?, ?)
					on conflict do nothing""", schoolId, roleId, permission);
		}
	}

	public List<Long> roleIdsOfUser(long userId) {
		return jdbc.queryForList("select role_id from user_role where user_id = ? order by role_id", Long.class, userId);
	}

	public Map<Long, List<Long>> roleIdsByUser(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<Long>> result = new java.util.HashMap<>();
		jdbc.query("select user_id, role_id from user_role where user_id = any(?) order by role_id",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", userIds.toArray())), rs -> {
					result.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>()).add(rs.getLong(2));
				});
		return result;
	}

	public void replaceUserRoles(long userId, Collection<Long> roleIds) {
		long schoolId = TenantContext.requireSchoolId();
		jdbc.update("delete from user_role where user_id = ?", userId);
		for (Long roleId : new HashSet<>(roleIds)) {
			jdbc.update("insert into user_role (school_id, user_id, role_id) values (?, ?, ?)", schoolId, userId,
					roleId);
		}
	}

	public long usersWithRole(long roleId) {
		Long count = jdbc.queryForObject("select count(*) from user_role where role_id = ?", Long.class, roleId);
		return count == null ? 0 : count;
	}

	public Map<Long, Long> userCountsByRole() {
		Map<Long, Long> result = new java.util.HashMap<>();
		jdbc.query("select role_id, count(*) from user_role group by role_id",
				rs -> {
					result.put(rs.getLong(1), rs.getLong(2));
				});
		return result;
	}

	/** Effective permissions of a user; the Administrator role grants everything. */
	public Set<String> effectivePermissions(long userId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select r.code, rp.permission
				from user_role ur
				join role r on r.id = ur.role_id
				left join role_permission rp on rp.role_id = r.id
				where ur.user_id = ?""", userId);
		if (rows.stream().anyMatch(row -> RoleTemplates.ADMIN.equals(row.get("code")))) {
			return Permissions.CODES;
		}
		return rows.stream()
			.map(row -> (String) row.get("permission"))
			.filter(java.util.Objects::nonNull)
			.filter(Permissions::exists)
			.collect(Collectors.toUnmodifiableSet());
	}

	public void recordStatusChange(long userId, String from, String to, String reason, String actorType,
			Long actorId) {
		jdbc.update("""
				insert into account_status_change (school_id, user_id, from_status, to_status, reason, actor_type, actor_id)
				values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireSchoolId(), userId, from, to, reason, actorType,
				actorId);
	}
}
