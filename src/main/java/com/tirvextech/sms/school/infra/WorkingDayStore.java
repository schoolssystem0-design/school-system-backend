package com.selectcode.sms.school.infra;

import com.selectcode.sms.shared.tenant.TenantContext;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Weekly working pattern per academic year ({@code working_day}). */
@Repository
public class WorkingDayStore {

	public enum DayType {
		FULL, HALF, OFF
	}

	/** Default for a new school: Monday to Saturday (FR 1.4 example), Sunday off. */
	public static Map<DayOfWeek, DayType> defaultWeek() {
		Map<DayOfWeek, DayType> week = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek day : DayOfWeek.values()) {
			week.put(day, day == DayOfWeek.SUNDAY ? DayType.OFF : DayType.FULL);
		}
		return week;
	}

	private final JdbcTemplate jdbc;

	WorkingDayStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<DayOfWeek, DayType> week(long academicYearId) {
		Map<DayOfWeek, DayType> week = new EnumMap<>(DayOfWeek.class);
		jdbc.query("select weekday, day_type from working_day where academic_year_id = ?", rs -> {
			week.put(DayOfWeek.of(rs.getInt(1)), DayType.valueOf(rs.getString(2)));
		}, academicYearId);
		if (week.isEmpty()) {
			return defaultWeek();
		}
		for (DayOfWeek day : DayOfWeek.values()) {
			week.putIfAbsent(day, DayType.OFF);
		}
		return week;
	}

	public void save(long academicYearId, Map<DayOfWeek, DayType> week) {
		long schoolId = TenantContext.requireSchoolId();
		jdbc.update("delete from working_day where academic_year_id = ?", academicYearId);
		for (var entry : week.entrySet()) {
			jdbc.update("insert into working_day (school_id, academic_year_id, weekday, day_type) values (?, ?, ?, ?)",
					schoolId, academicYearId, entry.getKey().getValue(), entry.getValue().name());
		}
	}
}
