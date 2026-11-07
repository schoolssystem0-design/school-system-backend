package com.selectcode.sms.platform.app;

import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.platform.domain.School;
import com.selectcode.sms.platform.infra.SchoolRepository;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes a school and all tenant rows (P-4 offboarding). Runs with {@code app.all_schools} so RLS allows deletes
 * platform-wide.
 */
@Component
class SchoolDeletion {

	private static final int MAX_PASSES = 64;

	private static final Pattern TABLE_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

	private static final Set<String> SKIP_TABLES = Set.of("school");

	private final SchoolRepository schools;

	private final SchoolUserAdministration userAdmin;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	SchoolDeletion(SchoolRepository schools, SchoolUserAdministration userAdmin, JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager) {
		this.schools = schools;
		this.userAdmin = userAdmin;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	void delete(long schoolId) {
		TenantContext.callAcrossAllSchools(() -> transaction.executeWithoutResult(tx -> {
			School school = schools.findById(schoolId)
				.orElseThrow(() -> ApiException.notFound("School"));
			TenantContext.runAsSchool(schoolId, () -> userAdmin.revokeAllSessions(schoolId));
			wipeTenantRows(schoolId);
			jdbc.update("delete from school_domain where school_id = ?", schoolId);
			schools.delete(school);
		}));
	}

	private void wipeTenantRows(long schoolId) {
		List<String> tables = jdbc.queryForList("""
				select distinct c.table_name
				from information_schema.columns c
				where c.table_schema = 'public'
				  and c.column_name = 'school_id'
				  and c.table_name <> 'school'
				order by c.table_name""", String.class);
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			int removed = 0;
			for (String table : tables) {
				if (!TABLE_NAME.matcher(table).matches() || SKIP_TABLES.contains(table)) {
					continue;
				}
				removed += jdbc.update("delete from " + table + " where school_id = ?", schoolId);
			}
			if (removed == 0) {
				return;
			}
		}
		throw new ApiException(HttpStatus.CONFLICT, "SCHOOL_DELETE_INCOMPLETE",
				"Could not remove all of this school's data. Try again or contact support.");
	}

	static void requireCodeMatch(String confirmCode, School school) {
		String expected = school.getCode().toUpperCase(Locale.ROOT);
		String given = confirmCode == null ? "" : confirmCode.trim().toUpperCase(Locale.ROOT);
		if (!expected.equals(given)) {
			throw ApiException.businessRule("CONFIRM_CODE_MISMATCH", "Type the school code " + expected + " to confirm");
		}
	}
}
