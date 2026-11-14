package com.tirvextech.sms.iam.app;

import com.tirvextech.sms.iam.domain.Role;
import com.tirvextech.sms.iam.domain.RoleTemplates;
import com.tirvextech.sms.iam.infra.RoleLinks;
import com.tirvextech.sms.iam.infra.RoleRepository;
import com.tirvextech.sms.shared.security.Permissions;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On startup, gives every school's built-in roles the permissions that new phases added to their templates, without
 * touching permissions a school removed earlier (only permissions newer than the role's template version are added).
 */
@Component
class SystemRoleUpgrader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SystemRoleUpgrader.class);

	private final RoleRepository roles;

	private final RoleLinks roleLinks;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final JdbcTemplate jdbc;

	SystemRoleUpgrader(RoleRepository roles, RoleLinks roleLinks, PlatformTransactionManager transactionManager,
			Clock clock, JdbcTemplate jdbc) {
		this.roles = roles;
		this.roleLinks = roleLinks;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<Long> schoolIds = TenantContext
			.callAcrossAllSchools(() -> jdbc.queryForList("select id from school order by id", Long.class));
		if (schoolIds.isEmpty()) {
			return;
		}
		int upgraded = 0;
		for (Long schoolId : schoolIds) {
			Integer count = TenantContext.callAsSchool(schoolId, () -> transaction.execute(tx -> upgradeRoles()));
			upgraded += count != null ? count : 0;
		}
		if (upgraded > 0) {
			log.info("Applied {} built-in role create/upgrade step(s); catalogue version {}", upgraded,
					Permissions.CATALOGUE_VERSION);
		}
	}

	private int upgradeRoles() {
		int count = 0;
		for (RoleTemplates.Template template : RoleTemplates.ALL) {
			if (roles.findByCode(template.code()).isEmpty()) {
				Role role = roles.save(new Role(template.code(), template.name(), template.description(), true,
						Permissions.CATALOGUE_VERSION));
				if (!role.isAdministrator()) {
					roleLinks.addRolePermissions(role.getId(), template.permissions());
				}
				count++;
			}
		}
		List<Role> outdated = roles.findBySystemRoleTrueAndTemplateVersionLessThan(Permissions.CATALOGUE_VERSION);
		for (Role role : outdated) {
			Set<String> template = RoleTemplates.ALL.stream()
				.filter(t -> t.code().equals(role.getCode()))
				.findFirst()
				.map(RoleTemplates.Template::permissions)
				.orElse(Set.of());
			List<String> added = Permissions.codesSince(role.getTemplateVersion())
				.stream()
				.filter(template::contains)
				.toList();
			roleLinks.addRolePermissions(role.getId(), added);
			role.upgradedTo(Permissions.CATALOGUE_VERSION, clock.instant());
			count++;
		}
		return count;
	}
}
