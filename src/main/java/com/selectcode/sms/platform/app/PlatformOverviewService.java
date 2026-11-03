package com.selectcode.sms.platform.app;

import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.platform.domain.School;
import com.selectcode.sms.platform.infra.SchoolRepository;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The super admin's all-schools overview (P-9). Runs across all schools; later phases add students, attendance, fee
 * collection, outstanding balances etc. to each row.
 */
@Service
public class PlatformOverviewService {

	public record SchoolRow(long id, String code, String name, String status, long users, long activeUsers,
			Instant lastLoginAt, String currentYear, long classes, Instant createdAt) {
	}

	public record Overview(int schools, int activeSchools, long users, List<SchoolRow> rows) {
	}

	public record PlatformAuditRow(long id, Instant at, String actorName, String action, Long schoolId,
			String schoolCode, String details, String ip) {
	}

	private final SchoolRepository schools;

	private final SchoolUserAdministration userAdmin;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	PlatformOverviewService(SchoolRepository schools, SchoolUserAdministration userAdmin, JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager) {
		this.schools = schools;
		this.userAdmin = userAdmin;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setReadOnly(true);
	}

	public Overview overview() {
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			Map<Long, SchoolUserAdministration.UserStats> stats = userAdmin.statsAcrossSchools()
				.stream()
				.collect(Collectors.toMap(SchoolUserAdministration.UserStats::schoolId, Function.identity()));
			Map<Long, String> currentYears = new java.util.HashMap<>();
			jdbc.query("select school_id, name from academic_year where status = 'CURRENT'",
					rs -> {
						currentYears.put(rs.getLong(1), rs.getString(2));
					});
			Map<Long, Long> classCounts = new java.util.HashMap<>();
			jdbc.query("select school_id, count(*) from school_class where status = 'ACTIVE' group by school_id",
					rs -> {
						classCounts.put(rs.getLong(1), rs.getLong(2));
					});
			List<SchoolRow> rows = schools.findAllByOrderByNameAsc().stream().map(s -> {
				var st = stats.get(s.getId());
				return new SchoolRow(s.getId(), s.getCode(), s.getName(), s.getStatus().name(),
						st == null ? 0 : st.users(), st == null ? 0 : st.activeUsers(),
						st == null ? null : st.lastLoginAt(), currentYears.get(s.getId()),
						classCounts.getOrDefault(s.getId(), 0L), s.getCreatedAt());
			}).toList();
			long totalUsers = rows.stream().mapToLong(SchoolRow::users).sum();
			int active = (int) rows.stream().filter(r -> School.Status.ACTIVE.name().equals(r.status())).count();
			return new Overview(rows.size(), active, totalUsers, rows);
		}));
	}

	/** Users of one school, for the super admin's school detail page. */
	public List<Map<String, Object>> schoolUsers(long schoolId) {
		return TenantContext.callAsSchool(schoolId, () -> transaction.execute(tx -> jdbc.queryForList("""
				select u.id, u.username, u.display_name as "displayName", u.email, u.status,
				       u.is_main_admin as "mainAdmin", u.last_login_at as "lastLoginAt",
				       coalesce(string_agg(r.name, ', ' order by r.name), '') as roles
				from app_user u
				left join user_role ur on ur.user_id = u.id
				left join role r on r.id = ur.role_id
				group by u.id order by u.is_main_admin desc, u.display_name""")));
	}

	public PageResponse<PlatformAuditRow> audit(int page, int size) {
		return transaction.execute(tx -> {
			Long total = jdbc.queryForObject("select count(*) from platform_audit_log", Long.class);
			List<PlatformAuditRow> rows = jdbc.query("""
					select a.id, a.at, p.name, a.action, a.school_id, s.code, a.details::text, a.ip
					from platform_audit_log a
					left join platform_user p on p.id = a.actor_id
					left join school s on s.id = a.school_id
					order by a.at desc, a.id desc limit ? offset ?""",
					(rs, i) -> new PlatformAuditRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
							rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getString(7), rs.getString(8)),
					size, (long) page * size);
			return PageResponse.of(rows, page, size, total == null ? 0 : total);
		});
	}
}
