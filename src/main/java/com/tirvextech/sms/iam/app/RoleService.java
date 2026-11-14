package com.selectcode.sms.iam.app;

import com.selectcode.sms.iam.domain.Role;
import com.selectcode.sms.iam.infra.RoleLinks;
import com.selectcode.sms.iam.infra.RoleRepository;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles and their permissions per school (FR 1.7, 2.5). */
@Service
public class RoleService {

	public record RoleView(long id, String code, String name, String description, boolean systemRole,
			boolean administrator, Set<String> permissions, long userCount, long version) {
	}

	public record RoleInput(String code, String name, String description, Set<String> permissions) {
	}

	private final RoleRepository roles;

	private final RoleLinks roleLinks;

	private final AuditService audit;

	private final Clock clock;

	RoleService(RoleRepository roles, RoleLinks roleLinks, AuditService audit, Clock clock) {
		this.roles = roles;
		this.roleLinks = roleLinks;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<RoleView> list() {
		Map<Long, Set<String>> permissions = roleLinks.permissionsByRole();
		Map<Long, Long> counts = roleLinks.userCountsByRole();
		return roles.findAllByOrderBySystemRoleDescNameAsc()
			.stream()
			.map(r -> view(r, permissions.getOrDefault(r.getId(), Set.of()), counts.getOrDefault(r.getId(), 0L)))
			.toList();
	}

	@Transactional
	public RoleView create(RoleInput input) {
		String code = input.code() == null ? "" : input.code().trim().toUpperCase();
		if (!code.matches("[A-Z][A-Z0-9_]{1,39}")) {
			throw ApiException.businessRule("ROLE_CODE_INVALID", "Use 2-40 capital letters, numbers or underscores");
		}
		if (roles.findByCode(code).isPresent()) {
			throw ApiException.conflict("ROLE_CODE_TAKEN", "A role with this code already exists");
		}
		Set<String> permissions = validPermissions(input.permissions());
		Role role = roles.save(new Role(code, required(input.name()), input.description(), false, 0));
		roleLinks.addRolePermissions(role.getId(), permissions);
		audit.record("ROLE_CREATED", "role", role.getId(), null, Map.of("code", code, "permissions", sorted(permissions)));
		return view(role, permissions, 0);
	}

	@Transactional
	public RoleView update(long id, RoleInput input) {
		Role role = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role"));
		if (role.isAdministrator()) {
			throw ApiException.conflict("ROLE_LOCKED", "The Administrator role always has every permission");
		}
		Set<String> before = roleLinks.permissionsOfRole(id);
		Set<String> permissions = validPermissions(input.permissions());
		role.rename(required(input.name()), input.description(), clock.instant());
		roleLinks.replaceRolePermissions(id, permissions);
		audit.record("ROLE_UPDATED", "role", id, Map.of("name", role.getName(), "permissions", sorted(before)),
				Map.of("name", input.name(), "permissions", sorted(permissions)));
		return view(role, permissions, roleLinks.usersWithRole(id));
	}

	@Transactional
	public void delete(long id) {
		Role role = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role"));
		if (role.isSystemRole()) {
			throw ApiException.conflict("ROLE_LOCKED", "Built-in roles cannot be deleted; remove their permissions instead");
		}
		long users = roleLinks.usersWithRole(id);
		if (users > 0) {
			throw ApiException.conflict("IN_USE", "This role is assigned to " + users + " user(s)");
		}
		roleLinks.replaceRolePermissions(id, Set.of());
		roles.delete(role);
		audit.record("ROLE_DELETED", "role", id, Map.of("code", role.getCode()), null);
	}

	private RoleView view(Role role, Set<String> permissions, long userCount) {
		Set<String> effective = role.isAdministrator() ? Permissions.CODES : permissions;
		return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isSystemRole(),
				role.isAdministrator(), new TreeSet<>(effective), userCount, role.getVersion());
	}

	private static Set<String> validPermissions(Set<String> permissions) {
		Set<String> result = permissions == null ? Set.of() : Set.copyOf(permissions);
		for (String p : result) {
			if (!Permissions.exists(p)) {
				throw ApiException.businessRule("PERMISSION_UNKNOWN", "Unknown permission: " + p);
			}
		}
		return result;
	}

	private static List<String> sorted(Set<String> values) {
		return values.stream().sorted().toList();
	}

	private static String required(String value) {
		if (value == null || value.isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name is required");
		}
		return value.trim();
	}
}
