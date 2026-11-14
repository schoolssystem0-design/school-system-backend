package com.selectcode.sms.settings;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.Money;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and changes school settings (FR 12). Values are cached per school and refreshed when changed. The cache is
 * per instance: fine for the single backend instance in v1 (add a short TTL or shared cache when scaling out).
 */
@Service
public class SettingsService {

	public record SettingView(String key, String group, String groupLabel, String label, String description,
			String type, List<String> options, Object value, Object defaultValue, boolean isDefault, Instant changedAt,
			String changedBy) {
	}

	public record HistoryEntry(Object value, String reason, Instant changedAt, String changedBy, String changedByType) {
	}

	private record Stored(Object value, Instant changedAt, String changedBy) {
	}

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final AuditService audit;

	private final Map<Long, Map<SettingKey, Stored>> cache = new ConcurrentHashMap<>();

	SettingsService(JdbcTemplate jdbc, JsonMapper json, AuditService audit) {
		this.jdbc = jdbc;
		this.json = json;
		this.audit = audit;
	}

	// ---- typed reads for other modules

	public boolean bool(SettingKey key) {
		return (Boolean) value(key, SettingKey.Type.BOOLEAN);
	}

	public int integer(SettingKey key) {
		return ((Number) value(key, SettingKey.Type.INTEGER)).intValue();
	}

	public Money money(SettingKey key) {
		return Money.of(String.valueOf(value(key, SettingKey.Type.MONEY)));
	}

	public LocalTime time(SettingKey key) {
		return LocalTime.parse(String.valueOf(value(key, SettingKey.Type.TIME)));
	}

	public String text(SettingKey key) {
		Object v = current().get(key) == null ? key.defaultValue : current().get(key).value();
		return String.valueOf(v);
	}

	private Object value(SettingKey key, SettingKey.Type expected) {
		if (key.type != expected) {
			throw new IllegalArgumentException(key + " is " + key.type + ", not " + expected);
		}
		Stored stored = current().get(key);
		return stored == null ? key.defaultValue : stored.value();
	}

	// ---- settings screen

	@Transactional(readOnly = true)
	public List<SettingView> list() {
		Map<SettingKey, Stored> values = current();
		return Arrays.stream(SettingKey.values()).map(k -> {
			Stored s = values.get(k);
			Object value = s == null ? k.defaultValue : s.value();
			return new SettingView(k.key(), k.group.name(), k.group.label, k.label, k.description, k.type.name(),
					k.options, value, k.defaultValue, s == null || value.equals(k.defaultValue),
					s == null ? null : s.changedAt(), s == null ? null : s.changedBy());
		}).toList();
	}

	@Transactional(readOnly = true)
	public List<HistoryEntry> history(String key) {
		SettingKey settingKey = SettingKey.fromKey(key).orElseThrow(() -> ApiException.notFound("Setting"));
		return jdbc.query("""
				select value::text, reason, changed_at, changed_by_name, changed_by_type
				from setting_value where setting_key = ? order by id desc limit 100""",
				(rs, i) -> new HistoryEntry(parse(settingKey, json.readTree(rs.getString(1))), rs.getString(2),
						rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5)),
				settingKey.key());
	}

	/** Changes a setting (FR 12.1, 12.2): validated by type, reason required, full history kept. */
	@Transactional
	public SettingView change(String key, Object rawValue, String reason) {
		SettingKey settingKey = SettingKey.fromKey(key).orElseThrow(() -> ApiException.notFound("Setting"));
		if (reason == null || reason.isBlank()) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give a reason for the change");
		}
		Object value = validate(settingKey, rawValue);
		Object before = current().get(settingKey) == null ? settingKey.defaultValue
				: current().get(settingKey).value();
		ActorPrincipal actor = CurrentActor.require();
		long schoolId = TenantContext.requireSchoolId();
		jdbc.update("""
				insert into setting_value (school_id, setting_key, value, reason, changed_by_type, changed_by_id, changed_by_name)
				values (?, ?, ?::jsonb, ?, ?, ?, ?)""", schoolId, settingKey.key(), json.writeValueAsString(value),
				reason.trim(), actor.actorType().name(), actor.actorId(), actor.displayName());
		audit.record("SETTING_CHANGED", "setting", null, Map.of("key", settingKey.key(), "value", before),
				Map.of("key", settingKey.key(), "value", value), reason.trim());
		evictAfterCommit(schoolId);
		cache.remove(schoolId);
		return list().stream().filter(v -> v.key().equals(settingKey.key())).findFirst().orElseThrow();
	}

	private Map<SettingKey, Stored> current() {
		long schoolId = TenantContext.requireSchoolId();
		return cache.computeIfAbsent(schoolId, id -> load());
	}

	private Map<SettingKey, Stored> load() {
		Map<SettingKey, Stored> result = new HashMap<>();
		jdbc.query("""
				select distinct on (setting_key) setting_key, value::text, changed_at, changed_by_name
				from setting_value order by setting_key, id desc""", rs -> {
			String valueJson = rs.getString(2);
			Instant changedAt = rs.getTimestamp(3).toInstant();
			String changedBy = rs.getString(4);
			SettingKey.fromKey(rs.getString(1))
				.ifPresent(k -> result.put(k, new Stored(parse(k, json.readTree(valueJson)), changedAt, changedBy)));
		});
		return result;
	}

	private void evictAfterCommit(long schoolId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					cache.remove(schoolId);
				}
			});
		}
	}

	private static Object parse(SettingKey key, JsonNode node) {
		return switch (key.type) {
			case BOOLEAN -> node.asBoolean();
			case INTEGER -> node.asInt();
			default -> node.asString();
		};
	}

	static Object validate(SettingKey key, Object raw) {
		if (raw == null) {
			throw invalid(key, "A value is required");
		}
		String text = String.valueOf(raw).trim();
		return switch (key.type) {
			case BOOLEAN -> {
				if (raw instanceof Boolean b) {
					yield b;
				}
				if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
					yield Boolean.parseBoolean(text);
				}
				throw invalid(key, "Choose yes or no");
			}
			case INTEGER -> {
				try {
					int value = raw instanceof Number n ? Math.toIntExact(n.longValue()) : Integer.parseInt(text);
					if (value < 0 || value > 100_000) {
						throw invalid(key, "Enter a number between 0 and 100000");
					}
					yield value;
				}
				catch (NumberFormatException | ArithmeticException ex) {
					throw invalid(key, "Enter a whole number");
				}
			}
			case MONEY -> {
				try {
					BigDecimal amount = new BigDecimal(text);
					if (amount.signum() < 0 || amount.scale() > 2) {
						throw invalid(key, "Enter an amount with at most 2 decimals");
					}
					yield Money.of(amount).toString();
				}
				catch (NumberFormatException ex) {
					throw invalid(key, "Enter an amount");
				}
			}
			case TIME -> {
				try {
					yield LocalTime.parse(text).withSecond(0).withNano(0).toString();
				}
				catch (DateTimeParseException ex) {
					throw invalid(key, "Enter a time as HH:MM");
				}
			}
			case ENUM -> {
				if (!key.options.contains(text)) {
					throw invalid(key, "Choose one of " + key.options);
				}
				yield text;
			}
			case TEXT -> {
				if (text.length() > 300) {
					throw invalid(key, "At most 300 characters");
				}
				yield text;
			}
		};
	}

	private static ApiException invalid(SettingKey key, String message) {
		return ApiException.businessRule("SETTING_INVALID", key.label + ": " + message);
	}
}
