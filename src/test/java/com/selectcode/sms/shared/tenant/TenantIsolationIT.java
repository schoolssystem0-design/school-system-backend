package com.selectcode.sms.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.iam.infra.AppUserRepository;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 0 spike A: proves the isolation layers in docs/ARCHITECTURE.md §4.0 (QA-TEN baseline). Each layer is tested
 * on its own, so one layer failing is caught even if another still hides it.
 */
class TenantIsolationIT extends IntegrationTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	JdbcTemplate app;

	TransactionTemplate tx;

	SeededSchool schoolA;

	SeededSchool schoolB;

	long adminA;

	long adminB;

	@BeforeEach
	void seedTwoSchools() {
		app = new JdbcTemplate(dataSource);
		tx = new TransactionTemplate(transactionManager);
		schoolA = TestData.school("A");
		schoolB = TestData.school("B");
		adminA = TestData.user(schoolA, "admin", "password-a");
		adminB = TestData.user(schoolB, "admin", "password-b");
		TestData.user(schoolB, "accountant", "password-b2");
	}

	@Test
	void appConnectsAsRestrictedRoleThatCannotBypassRls() {
		var role = app.queryForMap(
				"select rolsuper, rolbypassrls from pg_roles where rolname = current_user");
		assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
		assertThat(app.queryForObject("select pg_has_role(current_user, 'sms_app', 'member')", Boolean.class))
			.isTrue();
	}

	@Test
	void hibernateFilterReturnsOnlyCurrentSchool() {
		List<AppUser> visible = TenantContext.callAsSchool(schoolA.id(), () -> tx.execute(s -> users.findAll()));
		assertThat(visible).isNotEmpty().allSatisfy(u -> assertThat(u.getSchoolId()).isEqualTo(schoolA.id()));
		assertThat(visible).extracting(AppUser::getId).contains(adminA).doesNotContain(adminB);
	}

	@Test
	void otherSchoolsRecordIsNotFoundById() {
		// QA-TEN-001 at repository level: School A asking for School B's ID gets nothing.
		var result = TenantContext.callAsSchool(schoolA.id(), () -> tx.execute(s -> users.findById(adminB)));
		assertThat(result).isEmpty();
	}

	@Test
	void sameUsernameResolvesPerSchool() {
		// QA-TEN-002
		var inA = TenantContext.callAsSchool(schoolA.id(),
				() -> tx.execute(s -> users.findByUsernameIgnoreCase("ADMIN").orElseThrow().getId()));
		var inB = TenantContext.callAsSchool(schoolB.id(),
				() -> tx.execute(s -> users.findByUsernameIgnoreCase("admin").orElseThrow().getId()));
		assertThat(inA).isEqualTo(adminA);
		assertThat(inB).isEqualTo(adminB);
	}

	@Test
	void rowLevelSecurityFiltersRawSql() {
		// QA-TEN-004: even SQL that "forgets" the school filter only sees the current school.
		List<Long> schoolIds = TenantContext.callAsSchool(schoolA.id(),
				() -> app.queryForList("select school_id from app_user", Long.class));
		assertThat(schoolIds).isNotEmpty().containsOnly(schoolA.id());
	}

	@Test
	void noTenantMeansNoRowsAndNoWrites() {
		// QA-TEN-003
		assertThat(app.queryForObject("select count(*) from app_user", Long.class)).isZero();
		assertThatThrownBy(() -> app.update(
				"insert into app_user (school_id, username, display_name, password_hash) values (?, 'x', 'x', 'x')",
				schoolA.id()))
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void cannotWriteRowsForAnotherSchool() {
		assertThatThrownBy(() -> TenantContext.runAsSchool(schoolA.id(), () -> app.update(
				"insert into app_user (school_id, username, display_name, password_hash) values (?, 'intruder', 'x', 'x')",
				schoolB.id())))
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("row-level security");

		int updated = TenantContext.callAsSchool(schoolA.id(),
				() -> app.update("update app_user set display_name = 'hacked' where id = ?", adminB));
		assertThat(updated).isZero();
	}

	@Test
	void hibernateStampsCurrentSchoolOnInsert() {
		Long id = TenantContext.callAsSchool(schoolA.id(),
				() -> tx.execute(s -> users.save(new AppUser("clerk", "Office Clerk", "hash")).getId()));
		Long storedSchool = TestDatabase.superuser()
			.queryForObject("select school_id from app_user where id = ?", Long.class, id);
		assertThat(storedSchool).isEqualTo(schoolA.id());
	}

	@Test
	void compositeForeignKeyBlocksCrossSchoolReferences() {
		// QA-TEN-005: even the superuser cannot link School A's login event to School B's user.
		assertThatThrownBy(() -> TestDatabase.superuser()
			.update("insert into login_event (school_id, user_id, username, result) values (?, ?, 'admin', 'SUCCESS')",
					schoolA.id(), adminB))
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("login_event_user_fk");
	}

	@Test
	void allSchoolsModeSeesEverySchool() {
		List<Long> schoolIds = TenantContext.callAcrossAllSchools(
				() -> app.queryForList("select distinct school_id from app_user", Long.class));
		assertThat(schoolIds).contains(schoolA.id(), schoolB.id());

		List<AppUser> viaJpa = TenantContext.callAcrossAllSchools(() -> tx.execute(s -> users.findAll()));
		assertThat(viaJpa).extracting(AppUser::getId).contains(adminA, adminB);
	}

	@Test
	void contextDoesNotLeakBetweenTasksOnTheSameThread() throws Exception {
		// QA-TEN-011
		try (var executor = Executors.newSingleThreadExecutor()) {
			executor.submit(() -> TenantContext.callAsSchool(schoolA.id(), () -> users.count())).get();
			Future<Long> next = executor.submit(() -> {
				assertThat(TenantContext.schoolId()).isEmpty();
				return app.queryForObject("select count(*) from app_user", Long.class);
			});
			assertThat(next.get()).isZero();
		}
	}

	@Test
	void auditAndLoginHistoryAreInsertOnly() {
		TenantContext.runAsSchool(schoolA.id(), () -> {
			app.update("insert into audit_log (school_id, actor_type, action) values (?, 'SYSTEM', 'TEST')",
					schoolA.id());
			assertThatThrownBy(() -> app.update("update audit_log set action = 'CHANGED'"))
				.rootCause().hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from login_event"))
				.rootCause().hasMessageContaining("permission denied");
		});
	}

	@Test
	void everyTenantTableHasRowLevelSecurity() {
		// CI guard (DATA-MODEL §12): any table with a school_id column must have RLS, except platform tables.
		List<String> missing = TestDatabase.superuser().queryForList("""
				select c.relname from pg_class c
				join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
				join pg_attribute a on a.attrelid = c.oid and a.attname = 'school_id' and not a.attisdropped
				where c.relkind = 'r'
				  and c.relname not in ('school_domain', 'platform_audit_log')
				  and not (c.relrowsecurity and exists (select 1 from pg_policy p where p.polrelid = c.oid))
				""", String.class);
		assertThat(missing).as("tenant tables without RLS policy").isEmpty();
	}
}
