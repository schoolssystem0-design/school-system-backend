package com.selectcode.sms.iam.app;

import com.selectcode.sms.shared.web.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login history (FR 2.7) and activity history (FR 2.8) searches for the current school. */
@Service
public class SecurityLogQueries {

	private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Karachi");

	public record LoginRow(long id, Instant at, String username, Long userId, String displayName, String result,
			String ip, boolean newLocation, String userAgent) {
	}

	public record AuditRow(long id, Instant at, String actorType, Long actorId, String actorName, String action,
			String entityType, Long entityId, String before, String after, String reason, String ip) {
	}

	private final JdbcTemplate jdbc;

	SecurityLogQueries(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public PageResponse<LoginRow> logins(String username, String result, LocalDate from, LocalDate to, int page,
			int size) {
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> args = new ArrayList<>();
		if (username != null && !username.isBlank()) {
			where.append(" and lower(e.username) like ?");
			args.add("%" + username.trim().toLowerCase() + "%");
		}
		if (result != null && !result.isBlank()) {
			where.append(" and e.result = ?");
			args.add(result.trim().toUpperCase());
		}
		dateRange(where, args, "e.at", from, to);
		Long total = jdbc.queryForObject("select count(*) from login_event e" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<LoginRow> rows = jdbc.query("""
				select e.id, e.at, e.username, e.user_id, u.display_name, e.result, e.ip, e.new_location, e.user_agent
				from login_event e left join app_user u on u.id = e.user_id""" + where
				+ " order by e.at desc, e.id desc limit ? offset ?",
				(rs, i) -> new LoginRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
						(Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
						rs.getString(9)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public PageResponse<AuditRow> activity(String q, String entityType, LocalDate from, LocalDate to, int page,
			int size) {
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> args = new ArrayList<>();
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(a.action) like ? or lower(coalesce(a.reason, '')) like ?"
					+ " or lower(coalesce(a.after_data::text, '')) like ? or lower(coalesce(u.display_name, p.name, '')) like ?)");
			String like = "%" + q.trim().toLowerCase() + "%";
			args.add(like);
			args.add(like);
			args.add(like);
			args.add(like);
		}
		if (entityType != null && !entityType.isBlank()) {
			where.append(" and a.entity_type = ?");
			args.add(entityType.trim());
		}
		dateRange(where, args, "a.at", from, to);
		String fromClause = """
				 from audit_log a
				 left join app_user u on a.actor_type = 'USER' and u.id = a.actor_id
				 left join platform_user p on a.actor_type = 'PLATFORM' and p.id = a.actor_id""";
		Long total = jdbc.queryForObject("select count(*) " + fromClause + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<AuditRow> rows = jdbc.query("""
				select a.id, a.at, a.actor_type, a.actor_id,
				       case when a.actor_type = 'PLATFORM' then 'Super admin: ' || p.name else u.display_name end,
				       a.action, a.entity_type, a.entity_id, a.before_data::text, a.after_data::text, a.reason, a.ip"""
				+ " " + fromClause + where + " order by a.at desc, a.id desc limit ? offset ?",
				(rs, i) -> new AuditRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
						(Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7),
						(Long) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	private static void dateRange(StringBuilder where, List<Object> args, String column, LocalDate from,
			LocalDate to) {
		if (from != null) {
			where.append(" and ").append(column).append(" >= ?");
			args.add(Timestamp.from(from.atStartOfDay(SCHOOL_ZONE).toInstant()));
		}
		if (to != null) {
			where.append(" and ").append(column).append(" < ?");
			args.add(Timestamp.from(to.plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant()));
		}
	}
}
