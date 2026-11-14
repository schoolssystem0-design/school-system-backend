package com.tirvextech.sms.platform.app;

import com.tirvextech.sms.iam.SchoolUserAdministration;
import com.tirvextech.sms.platform.domain.School;
import com.tirvextech.sms.platform.infra.SchoolRepository;
import com.tirvextech.sms.shared.tenant.TenantContext;
import com.tirvextech.sms.shared.web.ApiException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes a school and all its rows (P-4 offboarding), in one transaction: either everything goes or nothing does.
 * <p>
 * The application role cannot delete records, so the rows are removed by the database function
 * {@code platform_delete_school} (migration V015), which runs with the owner's rights. The platform audit history is
 * kept: its entries for the school are unlinked.
 */
@Component
class SchoolDeletion {

	private static final Logger log = LoggerFactory.getLogger(SchoolDeletion.class);

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
		TenantContext.callAcrossAllSchools(() -> {
			transaction.executeWithoutResult(tx -> {
				if (!schools.existsById(schoolId)) {
					throw ApiException.notFound("School");
				}
				// Signs everyone out of the school first (their sessions live outside the school's tables)
				TenantContext.runAsSchool(schoolId, () -> userAdmin.revokeAllSessions(schoolId));
				try {
					jdbc.queryForObject("select platform_delete_school(?)", Long.class, schoolId);
				}
				catch (org.springframework.dao.DataAccessException e) {
					log.error("Deleting school {} failed; nothing was removed", schoolId, e);
					throw new ApiException(HttpStatus.CONFLICT, "SCHOOL_DELETE_INCOMPLETE",
							"Could not remove all of this school's data, so nothing was deleted. Please contact support.");
				}
			});
			return null;
		});
	}

	static void requireCodeMatch(String confirmCode, School school) {
		String expected = school.getCode().toUpperCase(Locale.ROOT);
		String given = confirmCode == null ? "" : confirmCode.trim().toUpperCase(Locale.ROOT);
		if (!expected.equals(given)) {
			throw ApiException.businessRule("CONFIRM_CODE_MISMATCH", "Type the school code " + expected + " to confirm");
		}
	}
}
