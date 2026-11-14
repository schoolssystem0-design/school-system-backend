package com.tirvextech.sms.reports.app;

import com.tirvextech.sms.shared.attendance.AttendancePercent;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a school compares with similar schools on the system (same size band). Privacy:
 * <ul>
 * <li>A school only ever sees its own figures and averages of others, never another school's name or numbers.</li>
 * <li>An average is shown only when at least {@link #MIN_SCHOOLS} other schools contribute to it.</li>
 * <li>Only percentages and ratios are compared; schools that turned "Compare with similar schools" off are left out
 * and do not see others' averages either.</li>
 * </ul>
 */
@Service
public class Benchmarks {

	public static final int MIN_SCHOOLS = 5;

	private static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private static final String SHARE_KEY = "general.benchmark_share";

	public record Measure(String key, String label, String unit, boolean higherIsBetter, String value, String average,
			Integer schools, String verdict, String hint) {
	}

	public record Benchmark(boolean sharing, String comparedWith, int studentsHere, List<Measure> measures) {
	}

	/** One measure, per school (null = no data for that school). */
	private record Metric(String key, String label, String unit, boolean higherIsBetter, Map<Long, BigDecimal> bySchool,
			BigDecimal margin, String hint) {
	}

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	Benchmarks(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setReadOnly(true);
		this.clock = clock;
	}

	public Benchmark compare(long schoolId) {
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> build(schoolId)));
	}

	private Benchmark build(long me) {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		Date month = Date.valueOf(today.minusDays(30));
		Date quarter = Date.valueOf(today.minusDays(90));
		Date yesterday = Date.valueOf(today.minusDays(1));

		// Who takes part: active schools that have not turned sharing off
		Set<Long> sharing = new HashSet<>(jdbc.queryForList("""
				select s.id from school s where s.status = 'ACTIVE' and coalesce((select v.value #>> '{}' from setting_value v
				  where v.school_id = s.id and v.setting_key = ? order by v.id desc limit 1), 'true') = 'true'""", Long.class,
				SHARE_KEY));
		Map<Long, Long> students = longs("select school_id, count(*) from student where status = 'ACTIVE' group by school_id");
		Map<Long, Long> teachers = longs("""
				select school_id, count(*) from staff where status = 'ACTIVE' and category = 'TEACHER' group by school_id""");

		List<Metric> metrics = new ArrayList<>();
		metrics.add(new Metric("attendance", "Student attendance (last 30 days)", "%", true,
				attendance("student_attendance", month), new BigDecimal("3"),
				"Check the registers marked absent most often, and turn on absence SMS so parents know the same day."));
		metrics.add(new Metric("fees", "Fees collected (due in the last 3 months)", "%", true, decimals("""
				select school_id, 100 * sum(paid) / nullif(sum(net + adjusted), 0) from fee_line_status
				where due_date between ? and ? and net + adjusted > 0 group by school_id""", quarter, yesterday),
				new BigDecimal("5"), "Send fee reminders by SMS before the due date and follow up the defaulters list weekly."));
		metrics.add(new Metric("tests", "Average test marks (last 3 months)", "%", true, decimals("""
				select t.school_id, 100 * sum(coalesce(m.obtained, 0)) / nullif(sum(t.max_marks), 0)
				from test_mark m join class_test t on t.id = m.class_test_id where t.test_date >= ? group by t.school_id""", quarter),
				new BigDecimal("3"), "Look at the subjects with the lowest averages in the class reports."));
		metrics.add(new Metric("staff", "Staff attendance (last 30 days)", "%", true, attendance("staff_attendance", month),
				new BigDecimal("3"), "Review late arrivals and absences in Staff attendance."));
		Map<Long, BigDecimal> ratio = new HashMap<>();
		students.forEach((school, n) -> {
			long t = teachers.getOrDefault(school, 0L);
			if (t > 0 && n > 0) {
				ratio.put(school, BigDecimal.valueOf(n).divide(BigDecimal.valueOf(t), 1, RoundingMode.HALF_UP));
			}
		});
		metrics.add(new Metric("ratio", "Students per teacher", "students", false, ratio, new BigDecimal("5"),
				"Larger classes than similar schools: consider another section or teacher where classes are biggest."));

		// Similar schools: the same size band (by students studying)
		long mine = students.getOrDefault(me, 0L);
		long[] band = band(mine);
		Set<Long> similar = new HashSet<>();
		for (long s : sharing) {
			long n = students.getOrDefault(s, 0L);
			if (s != me && n >= band[0] && n <= band[1]) {
				similar.add(s);
			}
		}
		Set<Long> others = new HashSet<>(sharing);
		others.remove(me);
		boolean meSharing = sharing.contains(me);
		String comparedWith = band[1] == Long.MAX_VALUE ? band[0] + "+ students" : band[0] + "–" + band[1] + " students";

		List<Measure> measures = new ArrayList<>();
		for (Metric m : metrics) {
			BigDecimal value = m.bySchool().get(me);
			String average = null;
			Integer count = null;
			if (meSharing) {
				List<BigDecimal> peers = similar.stream().map(m.bySchool()::get).filter(v -> v != null).toList();
				if (peers.size() < MIN_SCHOOLS) {
					peers = others.stream().map(m.bySchool()::get).filter(v -> v != null).toList(); // not enough similar: all
				}
				if (peers.size() >= MIN_SCHOOLS) {
					average = peers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
						.divide(BigDecimal.valueOf(peers.size()), 1, RoundingMode.HALF_UP).toPlainString();
					count = peers.size();
				}
			}
			String verdict = "NO_DATA";
			String hint = null;
			if (value != null && average != null) {
				BigDecimal diff = value.subtract(new BigDecimal(average));
				if (!m.higherIsBetter()) {
					diff = diff.negate();
				}
				verdict = diff.compareTo(m.margin()) >= 0 ? "BETTER" : diff.compareTo(m.margin().negate()) <= 0 ? "BEHIND" : "SIMILAR";
				if ("BEHIND".equals(verdict)) {
					hint = m.hint();
				}
			}
			else if (value != null) {
				verdict = "NOT_ENOUGH_SCHOOLS";
			}
			measures.add(new Measure(m.key(), m.label(), m.unit(), m.higherIsBetter(),
					value == null ? null : value.setScale(1, RoundingMode.HALF_UP).toPlainString(), average, count, verdict, hint));
		}
		return new Benchmark(meSharing, comparedWith, (int) mine, measures);
	}

	/** Size bands: 1–199, 200–499, 500–999, 1000+ students. */
	static long[] band(long students) {
		if (students < 200) {
			return new long[] { 1, 199 };
		}
		if (students < 500) {
			return new long[] { 200, 499 };
		}
		if (students < 1000) {
			return new long[] { 500, 999 };
		}
		return new long[] { 1000, Long.MAX_VALUE };
	}

	private Map<Long, BigDecimal> attendance(String table, Date from) {
		Map<Long, BigDecimal> out = new HashMap<>();
		jdbc.query("""
				select school_id, count(*) filter (where status = 'P'), count(*) filter (where status = 'L'),
				       count(*) filter (where status = 'HD'), count(*), count(*) filter (where status = 'LV')
				from %s where att_date >= ? group by school_id""".formatted(table), rs -> {
			String percent = AttendancePercent.of(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
			if (percent != null) {
				out.put(rs.getLong(1), new BigDecimal(percent));
			}
		}, from);
		return out;
	}

	private Map<Long, BigDecimal> decimals(String sql, Object... args) {
		Map<Long, BigDecimal> out = new HashMap<>();
		jdbc.query(sql, rs -> {
			BigDecimal v = rs.getBigDecimal(2);
			if (v != null) {
				out.put(rs.getLong(1), v.setScale(1, RoundingMode.HALF_UP));
			}
		}, args);
		return out;
	}

	private Map<Long, Long> longs(String sql) {
		Map<Long, Long> out = new HashMap<>();
		jdbc.query(sql, rs -> {
			out.put(rs.getLong(1), rs.getLong(2));
		});
		return out;
	}
}
