package com.selectcode.sms.iam;

import com.selectcode.sms.iam.app.AccountService;
import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.iam.domain.Role;
import com.selectcode.sms.iam.domain.RoleTemplates;
import com.selectcode.sms.iam.infra.AppUserRepository;
import com.selectcode.sms.iam.infra.PasswordTokens;
import com.selectcode.sms.iam.infra.RoleLinks;
import com.selectcode.sms.iam.infra.RoleRepository;
import com.selectcode.sms.iam.infra.SessionRevoker;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageSender;
import com.selectcode.sms.shared.messaging.OutgoingMessage;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Facade used by the platform module (super admin): provision a new school's roles and main administrator, issue
 * setup links, end sessions, and read user statistics. Callers run inside a transaction and the school's tenant
 * context unless stated otherwise.
 */
@Service
public class SchoolUserAdministration {

	public record NewAdmin(String displayName, String username, String email) {
	}

	/** One-time link for a user to set their password; shown once to the super admin and emailed if possible. */
	public record SetupLink(long userId, String username, String link, Instant expiresAt, boolean emailed) {
	}

	public record UserStats(long schoolId, long users, long activeUsers, Instant lastLoginAt) {
	}

	private final RoleRepository roles;

	private final RoleLinks roleLinks;

	private final AppUserRepository users;

	private final PasswordTokens tokens;

	private final PasswordEncoder passwordEncoder;

	private final AccountService accounts;

	private final SessionRevoker sessions;

	private final MessageSender messages;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final com.selectcode.sms.iam.app.UserAdminService userAdminService;

	SchoolUserAdministration(com.selectcode.sms.iam.app.UserAdminService userAdminService, RoleRepository roles, RoleLinks roleLinks, AppUserRepository users, PasswordTokens tokens,
			PasswordEncoder passwordEncoder, AccountService accounts, SessionRevoker sessions, MessageSender messages,
			AuditService audit, JdbcTemplate jdbc, Clock clock) {
		this.roles = roles;
		this.roleLinks = roleLinks;
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.accounts = accounts;
		this.sessions = sessions;
		this.messages = messages;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
		this.userAdminService = userAdminService;
	}

	/** Creates the built-in roles and the main administrator of a new school (tenant = the new school). */
	public SetupLink provisionSchool(String schoolName, NewAdmin admin) {
		Role adminRole = null;
		for (RoleTemplates.Template template : RoleTemplates.ALL) {
			Role role = roles.save(new Role(template.code(), template.name(), template.description(), true,
					Permissions.CATALOGUE_VERSION));
			if (role.isAdministrator()) {
				adminRole = role;
			}
			else {
				roleLinks.addRolePermissions(role.getId(), template.permissions());
			}
		}
		if (admin.email() == null || admin.email().isBlank()) {
			throw ApiException.businessRule("ADMIN_EMAIL_REQUIRED",
					"The main administrator needs an email for verification codes and password resets");
		}
		String username = admin.username() == null ? "" : admin.username().trim().toLowerCase(Locale.ROOT);
		if (!username.matches("[a-z0-9][a-z0-9._-]{2,59}")) {
			throw ApiException.businessRule("USERNAME_INVALID",
					"Use 3-60 lowercase letters, numbers, dots, dashes or underscores");
		}
		// Unusable random password until the setup link is used.
		AppUser user = AppUser.create(username, admin.displayName().trim(), admin.email().trim(), null,
				com.selectcode.sms.shared.security.DataScope.ALL, passwordEncoder.encode(UUID.randomUUID().toString()), false);
		user.markMainAdmin();
		user = users.save(user);
		roleLinks.replaceUserRoles(user.getId(), List.of(adminRole.getId()));
		audit.record("MAIN_ADMIN_CREATED", "app_user", user.getId(), null,
				Map.of("username", username, "email", user.getEmail()));
		return issueSetupLink(user, schoolName);
	}

	/** Issues a fresh setup link for any user of the current school (super admin assisted reset). */
	public SetupLink issueSetupLink(long userId, String schoolName) {
		AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
		SetupLink link = issueSetupLink(user, schoolName);
		sessions.revoke(TenantContext.requireSchoolId(), userId, null);
		return link;
	}

	private SetupLink issueSetupLink(AppUser user, String schoolName) {
		Instant now = clock.instant();
		var actor = CurrentActor.get();
		PasswordTokens.IssuedToken issued = tokens.issue(user.getId(), PasswordTokens.Purpose.SETUP, "LINK",
				now.plus(AccountService.SETUP_LINK_LIFETIME), actor.map(a -> a.actorType().name()).orElse("SYSTEM"),
				actor.map(a -> a.actorId()).orElse(null), now);
		String link = accounts.setupLink(issued.token());
		boolean emailed = user.getEmail() != null && messages.send(OutgoingMessage.email(user.getEmail(),
				"Set up your account for " + schoolName, """
						An account has been created for you at %s.

						School code and username are shown on the page. Open this link within 48 hours to choose your password:
						%s
						""".formatted(schoolName, link)));
		audit.record("SETUP_LINK_ISSUED", "app_user", user.getId(), null, Map.of("emailed", emailed));
		return new SetupLink(user.getId(), user.getUsername(), link, issued.expiresAt(), emailed);
	}

	/** Login for a staff member (FR 6.2, 7.2); returns the temporary password shown once. */
	public NewLogin createLoginForStaff(long staffId,
			String username, String displayName, String email, String phone, List<Long> roleIds,
			com.selectcode.sms.shared.security.DataScope dataScope) {
		var created = userAdminService.create(new com.selectcode.sms.iam.app.UserAdminService.UserInput(username,
				displayName, email, phone, roleIds, dataScope), staffId);
		return new NewLogin(created.userId(), created.username(), created.temporaryPassword());
	}

	/** Deactivates the login of a staff member who left (FR 6.7, 7.4); history is kept. */
	public void deactivateLoginOfStaff(long staffId, String reason) {
		users.findByStaffId(staffId)
			.filter(u -> u.getStatus() == AppUser.Status.ACTIVE && !u.isMainAdmin())
			.ifPresent(u -> userAdminService.deactivate(u.getId(), reason));
	}

	/** The login linked to a staff record, if any. */
	public java.util.Optional<LoginRef> loginOfStaff(long staffId) {
		return users.findByStaffId(staffId).map(u -> new LoginRef(u.getId(), u.getUsername(), u.getStatus().name()));
	}

	/** The staff record of the signed-in user (teachers' "own classes" scope). */
	public java.util.Optional<Long> staffIdOfUser(long userId) {
		return users.findById(userId).map(AppUser::getStaffId);
	}

	public record LoginRef(long userId, String username, String status) {
	}

	/** A new login; the temporary password is shown once and must be changed at first sign-in. */
	public record NewLogin(long userId, String username, String temporaryPassword) {
	}

	/** Ends every session of every user of the school (used when suspending it). Tenant = that school. */
	public int revokeAllSessions(long schoolId) {
		int count = 0;
		for (AppUser user : users.findAll()) {
			count += sessions.revoke(schoolId, user.getId(), null);
		}
		return count;
	}

	/** User statistics for all schools; must run across all schools (super admin overview). */
	public List<UserStats> statsAcrossSchools() {
		if (!TenantContext.isAllSchools()) {
			throw new IllegalStateException("statsAcrossSchools needs all-schools context");
		}
		return jdbc.query("""
				select school_id, count(*), count(*) filter (where status = 'ACTIVE'), max(last_login_at)
				from app_user group by school_id""",
				(rs, i) -> new UserStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
						rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()));
	}
}
