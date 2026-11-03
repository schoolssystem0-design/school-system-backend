package com.selectcode.sms.platform.app;

import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.iam.SchoolUserAdministration.NewAdmin;
import com.selectcode.sms.iam.SchoolUserAdministration.SetupLink;
import com.selectcode.sms.platform.domain.School;
import com.selectcode.sms.platform.domain.SchoolDomain;
import com.selectcode.sms.platform.infra.SchoolDomainRepository;
import com.selectcode.sms.platform.infra.SchoolRepository;
import com.selectcode.sms.school.SchoolSetup;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Super admin management of schools (P-2, P-3, P-4, P-6). Work inside a school runs in a transaction that starts after
 * the school is set in TenantContext, so row-level security and Hibernate's tenant filter both use that school.
 */
@Service
public class SchoolManagementService {

	private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,19}");

	private static final Pattern HOST = Pattern
		.compile("(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

	public record NewSchool(String code, String name, String adminName, String adminUsername, String adminEmail) {
	}

	public record SchoolCreated(long schoolId, String code, String name, SetupLink adminSetup) {
	}

	public record SchoolView(long id, String code, String name, String status, String suspendedReason,
			String timezone, Instant createdAt, List<DomainView> domains, long version) {
	}

	public record DomainView(long id, String host, boolean verified, boolean primary) {
	}

	private final SchoolRepository schools;

	private final SchoolDomainRepository domains;

	private final SchoolSetup schoolSetup;

	private final SchoolUserAdministration userAdmin;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final org.springframework.context.ApplicationEventPublisher events;

	SchoolManagementService(org.springframework.context.ApplicationEventPublisher events, SchoolRepository schools, SchoolDomainRepository domains, SchoolSetup schoolSetup,
			SchoolUserAdministration userAdmin, AuditService audit, JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.schools = schools;
		this.domains = domains;
		this.schoolSetup = schoolSetup;
		this.userAdmin = userAdmin;
		this.audit = audit;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.events = events;
	}

	public List<SchoolView> list() {
		return transaction.execute(tx -> schools.findAllByOrderByNameAsc().stream().map(this::view).toList());
	}

	public SchoolView get(long id) {
		return transaction.execute(tx -> view(find(id)));
	}

	/**
	 * Creates a school with its profile, built-in roles and main administrator in one transaction. Returns the
	 * administrator's one-time setup link (shown once, also emailed when email is configured).
	 */
	public SchoolCreated create(NewSchool input) {
		String code = input.code() == null ? "" : input.code().trim().toUpperCase(Locale.ROOT);
		if (!CODE.matcher(code).matches()) {
			throw ApiException.businessRule("SCHOOL_CODE_INVALID",
					"Use 2-20 capital letters, numbers or hyphens (e.g. NNS or NNS-MAIN)");
		}
		String name = required(input.name(), "School name");
		if (transaction.execute(tx -> schools.findByCode(code).isPresent())) {
			throw ApiException.conflict("SCHOOL_CODE_TAKEN", "School code " + code + " is already used");
		}
		NewAdmin admin = new NewAdmin(required(input.adminName(), "Administrator name"), input.adminUsername(),
				input.adminEmail());
		Long id = jdbc.queryForObject("select nextval(pg_get_serial_sequence('school', 'id'))", Long.class);
		SetupLink setup = TenantContext.callAsSchool(id, () -> transaction.execute(tx -> {
			jdbc.update("insert into school (id, code, name) values (?, ?, ?)", id, code, name);
			schoolSetup.initialise(name);
			SetupLink link = userAdmin.provisionSchool(name, admin);
			events.publishEvent(new com.selectcode.sms.shared.events.SchoolCreatedEvent(id, code, name));
			audit.recordPlatform("SCHOOL_CREATED", id, Map.of("code", code, "name", name));
			return link;
		}));
		return new SchoolCreated(id, code, name, setup);
	}

	public SchoolView rename(long id, String newName) {
		String name = required(newName, "School name");
		return transaction.execute(tx -> {
			School school = find(id);
			String before = school.getName();
			school.rename(name, clock.instant());
			audit.recordPlatform("SCHOOL_RENAMED", id, Map.of("from", before, "to", name));
			return view(school);
		});
	}

	/** Suspends a school: its users are signed out at once and cannot sign in; data is kept (P-4). */
	public SchoolView suspend(long id, String reason) {
		String why = required(reason, "Reason");
		SchoolView view = transaction.execute(tx -> {
			School school = find(id);
			school.suspend(why, clock.instant());
			audit.recordPlatform("SCHOOL_SUSPENDED", id, Map.of("reason", why));
			return view(school);
		});
		TenantContext.runAsSchool(id, () -> transaction.executeWithoutResult(tx -> userAdmin.revokeAllSessions(id)));
		return view;
	}

	public SchoolView reactivate(long id) {
		return transaction.execute(tx -> {
			School school = find(id);
			school.reactivate(clock.instant());
			audit.recordPlatform("SCHOOL_REACTIVATED", id, null);
			return view(school);
		});
	}

	public SchoolView addDomain(long id, String host, boolean primary) {
		String clean = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
		if (!HOST.matcher(clean).matches()) {
			throw ApiException.businessRule("HOST_INVALID", "Enter a domain like www.school.edu.pk");
		}
		return transaction.execute(tx -> {
			School school = find(id);
			if (domains.findByHostIgnoreCase(clean).isPresent()) {
				throw ApiException.conflict("HOST_TAKEN", "This domain is already mapped to a school");
			}
			domains.save(new SchoolDomain(id, clean, primary));
			audit.recordPlatform("SCHOOL_DOMAIN_ADDED", id, Map.of("host", clean));
			return view(school);
		});
	}

	public SchoolView removeDomain(long id, long domainId) {
		return transaction.execute(tx -> {
			School school = find(id);
			SchoolDomain domain = domains.findById(domainId)
				.filter(d -> d.getSchoolId() == id)
				.orElseThrow(() -> ApiException.notFound("Domain"));
			domains.delete(domain);
			audit.recordPlatform("SCHOOL_DOMAIN_REMOVED", id, Map.of("host", domain.getHost()));
			return view(school);
		});
	}

	/** New one-time setup link for any user of a school, e.g. a locked-out main administrator (P-3). */
	public SetupLink issueSetupLink(long schoolId, long userId) {
		String name = transaction.execute(tx -> find(schoolId).getName());
		return TenantContext.callAsSchool(schoolId,
				() -> transaction.execute(tx -> userAdmin.issueSetupLink(userId, name)));
	}

	School find(long id) {
		return schools.findById(id).orElseThrow(() -> ApiException.notFound("School"));
	}

	private SchoolView view(School school) {
		List<DomainView> mapped = domains.findBySchoolIdOrderByHostAsc(school.getId())
			.stream()
			.map(d -> new DomainView(d.getId(), d.getHost(), d.isVerified(), d.isPrimary()))
			.toList();
		return new SchoolView(school.getId(), school.getCode(), school.getName(), school.getStatus().name(),
				school.getSuspendedReason(), school.getTimezone(), school.getCreatedAt(), mapped, school.getVersion());
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", field + " is required");
		}
		return value.trim();
	}
}
