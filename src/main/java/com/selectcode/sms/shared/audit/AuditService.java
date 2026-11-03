package com.selectcode.sms.shared.audit;

import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activity history (FR 2.8, 11.12, 12.2). Called by application services inside their transaction, so the audit row
 * commits or rolls back together with the change. Rows are insert-only (database grants).
 *
 * <p>When the super admin acts inside a school, the row is written to that school's log (actor type PLATFORM) and to
 * the platform audit log.
 */
@Service
public class AuditService {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	public AuditService(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Records an action in the current school's activity history. */
	public void record(String action, String entityType, Long entityId, Object before, Object after, String reason) {
		long schoolId = TenantContext.requireSchoolId();
		ActorPrincipal actor = CurrentActor.get().orElse(null);
		ActorType actorType = actor == null ? ActorType.SYSTEM : actor.actorType();
		Long actorId = actor == null ? null : actor.actorId();
		String ip = clientIp();
		jdbc.update("""
				insert into audit_log (school_id, actor_type, actor_id, action, entity_type, entity_id,
				                       before_data, after_data, reason, ip)
				values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""", schoolId, actorType.name(), actorId, action,
				entityType, entityId, toJson(before), toJson(after), truncate(reason, 500), ip);
		if (actorType == ActorType.PLATFORM) {
			recordPlatform(action, schoolId, Map.of("entityType", String.valueOf(entityType), "entityId",
					String.valueOf(entityId)));
		}
	}

	public void record(String action, String entityType, Long entityId, Object before, Object after) {
		record(action, entityType, entityId, before, after, null);
	}

	/** Records a super admin action in the platform audit log. */
	public void recordPlatform(String action, Long schoolId, Object details) {
		Long actorId = CurrentActor.get()
			.filter(a -> a.actorType() == ActorType.PLATFORM)
			.map(ActorPrincipal::actorId)
			.orElse(null);
		jdbc.update("""
				insert into platform_audit_log (actor_id, action, school_id, details, ip)
				values (?, ?, ?, ?::jsonb, ?)""", actorId, action, schoolId, toJson(details), clientIp());
	}

	private String toJson(Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}

	private static String clientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			return truncate(request.getRemoteAddr(), 64);
		}
		return null;
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
