package com.selectcode.sms.iam.app;

import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.iam.domain.PasswordPolicy;
import com.selectcode.sms.iam.domain.Role;
import com.selectcode.sms.iam.infra.AppUserRepository;
import com.selectcode.sms.iam.infra.RoleLinks;
import com.selectcode.sms.iam.infra.RoleRepository;
import com.selectcode.sms.iam.infra.SessionRevoker;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrators manage the school's user accounts (FR 1.6, 1.7, 2.4, 2.5, 6.2, 7.2). */
@Service
public class UserAdminService {

	private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,59}");

	public record RoleRef(long id, String code, String name) {
	}

	public record UserView(long id, String username, String displayName, String email, String phone, String status,
			boolean locked, boolean mainAdmin, boolean mustChangePassword, String dataScope, List<RoleRef> roles,
			Instant lastLoginAt, Instant createdAt, long version) {
	}

	public record UserInput(String username, String displayName, String email, String phone, List<Long> roleIds,
			com.selectcode.sms.shared.security.DataScope dataScope) {
	}

	/** Shown to the administrator once; the user must change it at first sign-in. */
	public record TemporaryPassword(long userId, String username, String temporaryPassword) {
	}

	private final AppUserRepository users;

	private final RoleRepository roles;

	private final RoleLinks roleLinks;

	private final PasswordEncoder passwordEncoder;

	private final SessionRevoker sessions;

	private final AuditService audit;

	private final Clock clock;

	UserAdminService(AppUserRepository users, RoleRepository roles, RoleLinks roleLinks,
			PasswordEncoder passwordEncoder, SessionRevoker sessions, AuditService audit, Clock clock) {
		this.users = users;
		this.roles = roles;
		this.roleLinks = roleLinks;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<UserView> search(String q, AppUser.Status status, int page, int size) {
		Collection<AppUser.Status> statuses = status == null ? EnumSet.allOf(AppUser.Status.class) : Set.of(status);
		String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		var result = users.search(query, statuses, PageRequest.of(page, size));
		Map<Long, List<Long>> roleIds = roleLinks.roleIdsByUser(result.getContent().stream().map(AppUser::getId).toList());
		Map<Long, Role> roleById = roles.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
		return PageResponse.of(result, user -> view(user, roleIds.getOrDefault(user.getId(), List.of()), roleById));
	}

	@Transactional(readOnly = true)
	public UserView get(long id) {
		AppUser user = find(id);
		return view(user);
	}

	@Transactional
	public TemporaryPassword create(UserInput input) {
		return create(input, null);
	}

	/** Creates a login, optionally linked to a staff record (FR 6.2, 7.2). */
	@Transactional
	public TemporaryPassword create(UserInput input, Long staffId) {
		String username = normaliseUsername(input.username());
		if (users.findByUsernameIgnoreCase(username).isPresent()) {
			throw ApiException.conflict("USERNAME_TAKEN", "This username is already used in this school");
		}
		List<Long> roleIds = validRoleIds(input.roleIds());
		String temporary = PasswordPolicy.temporary();
		AppUser user = users.save(AppUser.create(username, required(input.displayName(), "Name"),
				blankToNull(input.email()), blankToNull(input.phone()), scope(input.dataScope()),
				passwordEncoder.encode(temporary), true));
		if (staffId != null) {
			if (users.findByStaffId(staffId).isPresent()) {
				throw ApiException.conflict("STAFF_HAS_LOGIN", "This staff member already has a login");
			}
			user.linkStaff(staffId);
		}
		roleLinks.replaceUserRoles(user.getId(), roleIds);
		audit.record("USER_CREATED", "app_user", user.getId(), null, snapshot(user, roleIds));
		return new TemporaryPassword(user.getId(), user.getUsername(), temporary);
	}

	@Transactional
	public UserView update(long id, UserInput input, long expectedVersion) {
		AppUser user = find(id);
		checkVersion(user, expectedVersion);
		List<Long> before = roleLinks.roleIdsOfUser(id);
		var snapshotBefore = snapshot(user, before);
		List<Long> roleIds = validRoleIds(input.roleIds());
		if (user.isMainAdmin() && !containsAdminRole(roleIds)) {
			throw ApiException.conflict("MAIN_ADMIN_ROLE", "The main administrator must keep the Administrator role");
		}
		user.updateDetails(required(input.displayName(), "Name"), blankToNull(input.email()),
				blankToNull(input.phone()), scope(input.dataScope()), clock.instant());
		roleLinks.replaceUserRoles(id, roleIds);
		users.flush();
		audit.record("USER_UPDATED", "app_user", id, snapshotBefore, snapshot(user, roleIds));
		return view(user);
	}

	@Transactional
	public UserView deactivate(long id, String reason) {
		AppUser user = find(id);
		ActorPrincipal actor = CurrentActor.require();
		if (user.isMainAdmin()) {
			throw ApiException.conflict("MAIN_ADMIN", "The main administrator cannot be deactivated");
		}
		if (actor.actorType() == com.selectcode.sms.shared.security.ActorType.USER && actor.actorId() == id) {
			throw ApiException.conflict("SELF_DEACTIVATION", "You cannot deactivate your own account");
		}
		changeStatus(user, AppUser.Status.INACTIVE, required(reason, "Reason"), actor);
		sessions.revoke(TenantContext.requireSchoolId(), id, null);
		return view(user);
	}

	@Transactional
	public UserView activate(long id, String reason) {
		AppUser user = find(id);
		changeStatus(user, AppUser.Status.ACTIVE, required(reason, "Reason"), CurrentActor.require());
		return view(user);
	}

	@Transactional
	public UserView unlock(long id) {
		AppUser user = find(id);
		user.unlock(clock.instant());
		audit.record("USER_UNLOCKED", "app_user", id, null, null);
		return view(user);
	}

	/** Administrator-assisted reset (FR 2.3): a temporary password that must be changed at next sign-in. */
	@Transactional
	public TemporaryPassword resetPassword(long id) {
		AppUser user = find(id);
		String temporary = PasswordPolicy.temporary();
		user.setPassword(passwordEncoder.encode(temporary), true, clock.instant());
		audit.record("PASSWORD_RESET_BY_ADMIN", "app_user", id, null, null);
		sessions.revoke(TenantContext.requireSchoolId(), id, null);
		return new TemporaryPassword(id, user.getUsername(), temporary);
	}

	private void changeStatus(AppUser user, AppUser.Status to, String reason, ActorPrincipal actor) {
		AppUser.Status from = user.getStatus();
		if (from == to) {
			return;
		}
		user.changeStatus(to, clock.instant());
		roleLinks.recordStatusChange(user.getId(), from.name(), to.name(), reason, actor.actorType().name(),
				actor.actorId());
		audit.record(to == AppUser.Status.ACTIVE ? "USER_ACTIVATED" : "USER_DEACTIVATED", "app_user", user.getId(),
				Map.of("status", from.name()), Map.of("status", to.name()), reason);
	}

	private AppUser find(long id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
	}

	private List<Long> validRoleIds(List<Long> roleIds) {
		if (roleIds == null || roleIds.isEmpty()) {
			throw ApiException.businessRule("ROLE_REQUIRED", "Choose at least one role");
		}
		List<Long> distinct = roleIds.stream().distinct().toList();
		if (roles.findAllById(distinct).size() != distinct.size()) {
			throw ApiException.businessRule("ROLE_UNKNOWN", "One of the selected roles does not exist");
		}
		return distinct;
	}

	private boolean containsAdminRole(List<Long> roleIds) {
		return roles.findAllById(roleIds).stream().anyMatch(Role::isAdministrator);
	}

	private UserView view(AppUser user) {
		Map<Long, Role> roleById = roles.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
		return view(user, roleLinks.roleIdsOfUser(user.getId()), roleById);
	}

	private UserView view(AppUser user, List<Long> roleIds, Map<Long, Role> roleById) {
		List<RoleRef> refs = roleIds.stream()
			.map(roleById::get)
			.filter(java.util.Objects::nonNull)
			.map(r -> new RoleRef(r.getId(), r.getCode(), r.getName()))
			.toList();
		return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getPhone(),
				user.getStatus().name(), user.isLocked(clock.instant()), user.isMainAdmin(),
				user.isMustChangePassword(), user.getDataScope().name(), refs, user.getLastLoginAt(),
				user.getCreatedAt(), user.getVersion());
	}

	private static Map<String, Object> snapshot(AppUser user, List<Long> roleIds) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("username", user.getUsername());
		map.put("displayName", user.getDisplayName());
		map.put("email", user.getEmail());
		map.put("phone", user.getPhone());
		map.put("dataScope", user.getDataScope().name());
		map.put("roleIds", roleIds);
		return map;
	}

	static String normaliseUsername(String username) {
		String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
		if (!USERNAME.matcher(value).matches()) {
			throw ApiException.businessRule("USERNAME_INVALID",
					"Use 3-60 lowercase letters, numbers, dots, dashes or underscores");
		}
		return value;
	}

	private static void checkVersion(AppUser user, long expected) {
		if (user.getVersion() != expected) {
			throw ApiException.conflict("CONFLICT_VERSION", "The user was changed by someone else. Reload and try again.");
		}
	}

	private static com.selectcode.sms.shared.security.DataScope scope(com.selectcode.sms.shared.security.DataScope scope) {
		return scope == null ? com.selectcode.sms.shared.security.DataScope.ALL : scope;
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", field + " is required");
		}
		return value.trim();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
