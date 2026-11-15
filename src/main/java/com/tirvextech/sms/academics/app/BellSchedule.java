package com.tirvextech.sms.academics.app;

import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.tenant.TenantContext;
import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.year.AcademicYearLookup;
import com.tirvextech.sms.shared.year.YearContext;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The school day of an academic year: teaching periods and breaks in order, with their times. Teaching periods are
 * numbered 1, 2, 3... in order; the timetable and the automatic timetable use these numbers and times.
 */
@Service
public class BellSchedule {

	public static final int MAX_PERIODS = 15;

	/** periodNo: the teaching period's number (null for a break). onHalfDay: also happens on half days. */
	public record Slot(String kind, String label, LocalTime startTime, LocalTime endTime, boolean onHalfDay,
			Integer periodNo) {

		public boolean period() {
			return "PERIOD".equals(kind);
		}
	}

	private final JdbcTemplate jdbc;

	private final AcademicYearLookup years;

	private final YearContext yearContext;

	private final AuditService audit;

	BellSchedule(JdbcTemplate jdbc, AcademicYearLookup years, YearContext yearContext, AuditService audit) {
		this.jdbc = jdbc;
		this.years = years;
		this.yearContext = yearContext;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<Slot> slots(long yearId) {
		years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		List<Slot> raw = jdbc.query("""
				select kind, label, start_time, end_time, on_half_day from bell_slot where academic_year_id = ? order by seq""",
				(rs, i) -> new Slot(rs.getString(1), rs.getString(2), rs.getObject(3, LocalTime.class),
						rs.getObject(4, LocalTime.class), rs.getBoolean(5), null), yearId);
		return numbered(raw);
	}

	/** Replaces the bell schedule. Slots must be in time order without overlapping. */
	@Transactional
	public List<Slot> save(long yearId, List<Slot> slots) {
		var year = years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		yearContext.requireWritable(year);
		if (slots.size() > 30) {
			throw ApiException.businessRule("BELL_TOO_LONG", "At most 30 periods and breaks");
		}
		long periods = slots.stream().filter(s -> !"BREAK".equals(s.kind())).count();
		if (periods > MAX_PERIODS) {
			throw ApiException.businessRule("BELL_TOO_MANY_PERIODS", "At most " + MAX_PERIODS + " teaching periods a day");
		}
		LocalTime previousEnd = null;
		for (Slot s : slots) {
			if (!"PERIOD".equals(s.kind()) && !"BREAK".equals(s.kind())) {
				throw ApiException.businessRule("BELL_KIND_INVALID", "Each row is a period or a break");
			}
			if (s.startTime() == null || s.endTime() == null || !s.endTime().isAfter(s.startTime())) {
				throw ApiException.businessRule("BELL_TIME_INVALID", "Every period and break needs a start and a later end time");
			}
			if (previousEnd != null && s.startTime().isBefore(previousEnd)) {
				throw ApiException.businessRule("BELL_OVERLAP", "Rows must be in time order and must not overlap ("
						+ s.startTime() + " starts before the row above ends)");
			}
			previousEnd = s.endTime();
		}
		jdbc.update("delete from bell_slot where academic_year_id = ?", yearId);
		long schoolId = TenantContext.requireSchoolId();
		int seq = 1;
		for (Slot s : slots) {
			String label = s.label() == null || s.label().isBlank() ? null : s.label().trim();
			jdbc.update("""
					insert into bell_slot (school_id, academic_year_id, seq, kind, label, start_time, end_time, on_half_day)
					values (?, ?, ?, ?, ?, ?, ?, ?)""", schoolId, yearId, seq++, s.kind(),
					label == null ? null : label.substring(0, Math.min(40, label.length())), s.startTime(), s.endTime(),
					s.onHalfDay());
		}
		audit.record("BELL_SCHEDULE_SAVED", "academic_year", yearId, null, Map.of("periods", periods,
				"breaks", slots.size() - periods));
		return slots(yearId);
	}

	private static List<Slot> numbered(List<Slot> raw) {
		List<Slot> out = new ArrayList<>();
		int n = 0;
		for (Slot s : raw) {
			out.add(new Slot(s.kind(), s.label(), s.startTime(), s.endTime(), s.onHalfDay(), s.period() ? ++n : null));
		}
		return out;
	}
}
