package com.selectcode.sms.shared.persistence;

import com.selectcode.sms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free per-school counters (family numbers, enquiry numbers, employee numbers, certificate serials). The row is
 * locked by the UPDATE, so concurrent callers get distinct values; a rolled-back caller's value is reused.
 */
@Component
public class NumberSequences {

	private final JdbcTemplate jdbc;

	NumberSequences(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Must run inside the caller's transaction (so the number is only used if the caller commits). */
	@Transactional(propagation = Propagation.MANDATORY)
	public long next(String name) {
		long schoolId = TenantContext.requireSchoolId();
		jdbc.update("insert into number_sequence (school_id, name, next_value) values (?, ?, 1) on conflict do nothing",
				schoolId, name);
		Long value = jdbc.queryForObject("""
				update number_sequence set next_value = next_value + 1
				where school_id = ? and name = ? returning next_value - 1""", Long.class, schoolId, name);
		return value == null ? 1 : value;
	}

	/** e.g. format("F", 7, 4) = "F-0007". */
	public static String format(String prefix, long value, int digits) {
		return prefix + "-" + String.format("%0" + digits + "d", value);
	}
}
