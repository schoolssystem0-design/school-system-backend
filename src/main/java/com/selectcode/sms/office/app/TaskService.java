package com.selectcode.sms.office.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared task list (FR 3.5). A task is seen by whoever created it and the colleagues it is shared with, and any of
 * them can mark it complete. The super admin sees every task of the school.
 */
@Service
public class TaskService {

	public record Colleague(long id, String name) {
	}

	public record TaskView(long id, String description, LocalDate deadline, String priority, String status,
			String createdBy, Instant createdAt, String completedBy, Instant completedAt, boolean overdue,
			List<Colleague> sharedWith) {
	}

	public record TaskInput(String description, LocalDate deadline, String priority, List<Long> shareWith) {
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final Clock clock;

	TaskService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.clock = clock;
	}

	/** Active colleagues, to share a task with or assign a complaint to. */
	@Transactional(readOnly = true)
	public List<Colleague> colleagues() {
		return jdbc.query("select id, display_name from app_user where status = 'ACTIVE' order by display_name",
				(rs, i) -> new Colleague(rs.getLong(1), rs.getString(2)));
	}

	/** SQL condition: the task is the current user's, as creator or through a share. */
	private static String mineCondition() {
		var actor = CurrentActor.require();
		if (CurrentActor.isSuperAdmin() || actor.actorType() != ActorType.USER) {
			return " true ";
		}
		long me = actor.actorId();
		return " (t.created_by_user = " + me + " or exists (select 1 from task_share s where s.task_id = t.id and s.user_id = "
				+ me + ")) ";
	}

	@Transactional(readOnly = true)
	public List<TaskView> list(boolean includeDone) {
		String sql = "select t.id, t.description, t.deadline, t.priority, t.status, t.created_by, t.created_at, t.completed_by, "
				+ "t.completed_at from task t where " + mineCondition() + (includeDone ? "" : " and t.status = 'OPEN' ")
				+ " order by t.status desc, case t.priority when 'URGENT' then 0 else 1 end, t.deadline nulls last, t.id desc limit 200";
		LocalDate today = MoneyInput.today(clock);
		return jdbc.query(sql, (rs, i) -> {
			long id = rs.getLong(1);
			LocalDate deadline = rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate();
			String status = rs.getString(5);
			return new TaskView(id, rs.getString(2), deadline, rs.getString(4), status, rs.getString(6),
					rs.getTimestamp(7).toInstant(), rs.getString(8),
					rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
					"OPEN".equals(status) && deadline != null && deadline.isBefore(today), shares(id));
		});
	}

	private List<Colleague> shares(long taskId) {
		return jdbc.query("""
				select u.id, u.display_name from task_share s join app_user u on u.id = s.user_id
				where s.task_id = ? order by u.display_name""", (rs, i) -> new Colleague(rs.getLong(1), rs.getString(2)),
				taskId);
	}

	@Transactional
	public TaskView create(TaskInput in) {
		String description = PakFormats.trimToNull(in.description());
		if (description == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Describe the task");
		}
		String priority = in.priority() == null ? "NORMAL" : in.priority();
		if (!List.of("NORMAL", "URGENT").contains(priority)) {
			throw ApiException.businessRule("PRIORITY_INVALID", "Choose normal or urgent");
		}
		var actor = CurrentActor.require();
		Long id = jdbc.queryForObject("""
				insert into task (school_id, description, deadline, priority, created_by_user, created_by)
				values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), description,
				in.deadline() == null ? null : Date.valueOf(in.deadline()), priority,
				actor.actorType() == ActorType.USER ? actor.actorId() : null, actor.displayName());
		share(id, in.shareWith());
		audit.record("TASK_CREATED", "task", id, null, Map.of("priority", priority));
		return find(id);
	}

	private void share(long taskId, List<Long> userIds) {
		if (userIds == null) {
			return;
		}
		for (long userId : userIds.stream().distinct().toList()) {
			if (jdbc.queryForList("select id from app_user where id = ? and status = 'ACTIVE'", Long.class, userId).isEmpty()) {
				throw ApiException.notFound("Colleague");
			}
			jdbc.update("insert into task_share (school_id, task_id, user_id) values (?, ?, ?)",
					TenantContext.requireSchoolId(), taskId, userId);
		}
	}

	@Transactional
	public TaskView setShares(long id, List<Long> userIds) {
		find(id);
		jdbc.update("delete from task_share where task_id = ?", id);
		share(id, userIds);
		audit.record("TASK_SHARED", "task", id, null, Map.of("people", userIds == null ? 0 : userIds.size()));
		return find(id);
	}

	@Transactional
	public TaskView setDone(long id, boolean done) {
		find(id);
		jdbc.update("update task set status = ?, completed_by = ?, completed_at = ? where id = ?", done ? "DONE" : "OPEN",
				done ? CurrentActor.require().displayName() : null, done ? Timestamp.from(clock.instant()) : null, id);
		audit.record(done ? "TASK_COMPLETED" : "TASK_REOPENED", "task", id, null, Map.of("done", done));
		return find(id);
	}

	/** The task, if the current user is involved in it; otherwise it does not exist for them. */
	private TaskView find(long id) {
		return list(true).stream().filter(t -> t.id() == id).findFirst().orElseGet(() -> {
			// Beyond the newest 200: look it up directly
			List<Long> found = jdbc.queryForList("select t.id from task t where t.id = ? and " + mineCondition(), Long.class, id);
			if (found.isEmpty()) {
				throw ApiException.notFound("Task");
			}
			return jdbc.queryForObject("""
					select t.description, t.deadline, t.priority, t.status, t.created_by, t.created_at, t.completed_by,
					       t.completed_at from task t where t.id = ?""", (rs, i) -> new TaskView(id, rs.getString(1),
					rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getString(7),
					rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), false, shares(id)), id);
		});
	}
}
