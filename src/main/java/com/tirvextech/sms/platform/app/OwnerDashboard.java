package com.tirvextech.sms.platform.app;

import com.tirvextech.sms.shared.messaging.smsgate.SmsGateways;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's business view (Tirvex Tech): how many schools pay, how trials are going, which schools are healthy and
 * which may leave, growth, and SMS problems. Built only from data the system already keeps (sign-ins, registers,
 * payments, messages, subscriptions); nothing about pupils is shown, only counts.
 */
@Service
public class OwnerDashboard {

	public record Headline(int schools, int paying, int onTrial, int trialsEndingThisWeek, int paused, long students,
			long activeUsers7d, String expectedThisMonth, String receivedThisMonth, String overdue) {
	}

	public record Conversion(int trialsThisMonth, int convertedThisMonth, int trialsAllTime, int convertedAllTime,
			Integer ratePercent) {
	}

	public record TrialRow(long schoolId, String code, String name, Instant trialEndsAt, int daysLeft, long students,
			long activeUsers7d, long registers7d, Instant lastSignIn, String interest) {
	}

	public record SchoolHealth(long schoolId, String code, String name, String plan, String standing, Instant lastSignIn,
			long activeUsers7d, long students, long registers7d, long payments30d, long sms30d, long smsFailed30d,
			String smsPhone, String health, List<String> reasons) {
	}

	public record GrowthPoint(String month, int schools, long students) {
	}

	public record BusinessDashboard(Headline headline, Conversion conversion, List<TrialRow> trials,
			List<SchoolHealth> schools, List<GrowthPoint> growth) {
	}

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final SubscriptionService billing;

	private final Clock clock;

	OwnerDashboard(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, SubscriptionService billing,
			Clock clock) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setReadOnly(true);
		this.billing = billing;
		this.clock = clock;
	}

	public BusinessDashboard dashboard() {
		var money = billing.overview().summary();
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> build(money)));
	}

	private BusinessDashboard build(SubscriptionService.BillingSummary money) {
		Instant now = clock.instant();
		LocalDate today = billing.today();
		Date weekAgo = Date.valueOf(today.minusDays(7));
		Date monthAgo = Date.valueOf(today.minusDays(30));
		Timestamp since7 = Timestamp.from(now.minus(Duration.ofDays(7)));
		Timestamp since30 = Timestamp.from(now.minus(Duration.ofDays(30)));

		Map<Long, Long> students = counts("select school_id, count(*) from student where status = 'ACTIVE' group by school_id");
		Map<Long, Long> users7 = counts("""
				select school_id, count(distinct user_id) from login_event where result = 'SUCCESS' and at >= ? group by school_id""",
				since7);
		Map<Long, Long> registers7 = counts("""
				select school_id, count(distinct (section_id, att_date)) from student_attendance where att_date >= ?
				group by school_id""", weekAgo);
		Map<Long, Long> payments30 = counts("select school_id, count(*) from payment where payment_date >= ? group by school_id",
				monthAgo);
		Map<Long, Long> sms30 = counts("""
				select school_id, count(*) from message_outbox where channel = 'SMS' and status in ('SENT', 'DELIVERED')
				  and created_at >= ? group by school_id""", since30);
		Map<Long, Long> failed30 = counts("""
				select school_id, count(*) from message_outbox where channel = 'SMS' and status = 'FAILED' and created_at >= ?
				group by school_id""", since30);
		Map<Long, Instant> lastSignIn = new HashMap<>();
		jdbc.query("select school_id, max(at) from login_event where result = 'SUCCESS' group by school_id",
				rs -> {
					lastSignIn.put(rs.getLong(1), rs.getTimestamp(2).toInstant());
				});
		Map<Long, String> phones = new HashMap<>();
		jdbc.query("select school_id, enabled, last_seen_at from sms_gateway", rs -> {
			Timestamp seen = rs.getTimestamp(3);
			boolean online = seen != null
					&& seen.toInstant().isAfter(now.minus(Duration.ofMinutes(SmsGateways.OFFLINE_AFTER_MINUTES)));
			phones.put(rs.getLong(1), !rs.getBoolean(2) ? "OFF" : online ? "ONLINE" : "OFFLINE");
		});
		Map<Long, String> plans = new HashMap<>();
		jdbc.query("select subscribed_school_id, cycle, status from subscription", rs -> {
			plans.put(rs.getLong(1), "CANCELLED".equals(rs.getString(3)) ? "Cancelled"
					: "YEARLY".equals(rs.getString(2)) ? "Yearly" : "Monthly");
		});
		Map<Long, String> standing = new HashMap<>();
		billing.overview().subscriptions().forEach(s -> standing.put(s.schoolId(), s.standing()));

		List<SchoolHealth> schools = new ArrayList<>();
		List<TrialRow> trials = new ArrayList<>();
		int paying = 0, onTrial = 0, endingSoon = 0;
		for (Map<String, Object> s : jdbc.queryForList("""
				select id, code, name, status, trial_ends_at, created_at from school order by name""")) {
			long id = ((Number) s.get("id")).longValue();
			String code = (String) s.get("code"), name = (String) s.get("name");
			Instant trialEnds = s.get("trial_ends_at") == null ? null : ((Timestamp) s.get("trial_ends_at")).toInstant();
			Instant created = ((Timestamp) s.get("created_at")).toInstant();
			boolean suspended = !"ACTIVE".equals(s.get("status"));
			String plan = trialEnds != null ? "Trial" : plans.getOrDefault(id, "No plan");
			String billingStanding = standing.getOrDefault(id, trialEnds != null ? "TRIAL" : "NONE");
			if (("Monthly".equals(plan) || "Yearly".equals(plan)) && !"PAUSED".equals(billingStanding)) {
				paying++;
			}
			Instant last = lastSignIn.get(id);
			long u7 = users7.getOrDefault(id, 0L), st = students.getOrDefault(id, 0L), r7 = registers7.getOrDefault(id, 0L);
			if (trialEnds != null && trialEnds.isAfter(now)) {
				onTrial++;
				int daysLeft = (int) Math.ceil(Duration.between(now, trialEnds).toHours() / 24.0);
				if (daysLeft <= 7) {
					endingSoon++;
				}
				String interest = u7 >= 2 && (st >= 20 || r7 > 0) ? "HOT" : last != null && last.isAfter(since7.toInstant()) ? "WARM" : "COLD";
				trials.add(new TrialRow(id, code, name, trialEnds, daysLeft, st, u7, r7, last, interest));
			}

			List<String> reasons = new ArrayList<>();
			String health = "GREEN";
			if (suspended) {
				reasons.add("Suspended");
				health = "RED";
			}
			if ("PAUSED".equals(billingStanding)) {
				reasons.add("Access paused: invoice unpaid");
				health = "RED";
			}
			else if ("OVERDUE".equals(billingStanding)) {
				reasons.add("Invoice overdue");
				health = worse(health, "AMBER");
			}
			if (trialEnds != null && !trialEnds.isAfter(now)) {
				reasons.add("Trial ended");
				health = "RED";
			}
			if (last == null) {
				if (created.isBefore(now.minus(Duration.ofDays(7)))) {
					reasons.add("Nobody has signed in yet");
					health = "RED";
				}
			}
			else if (last.isBefore(now.minus(Duration.ofDays(14)))) {
				reasons.add("No sign-in for " + Duration.between(last, now).toDays() + " days");
				health = "RED";
			}
			else if (last.isBefore(now.minus(Duration.ofDays(7)))) {
				reasons.add("No sign-in this week");
				health = worse(health, "AMBER");
			}
			if (st > 0 && r7 == 0) {
				reasons.add("No attendance taken this week");
				health = worse(health, "AMBER");
			}
			String phone = phones.getOrDefault(id, "NOT_SET");
			if ("OFFLINE".equals(phone)) {
				reasons.add("SMS phone offline");
				health = worse(health, "AMBER");
			}
			long failed = failed30.getOrDefault(id, 0L);
			if (failed >= 10) {
				reasons.add(failed + " SMS failed this month");
				health = worse(health, "AMBER");
			}
			schools.add(new SchoolHealth(id, code, name, plan, billingStanding, last, u7, st, r7,
					payments30.getOrDefault(id, 0L), sms30.getOrDefault(id, 0L), failed, phone, health, reasons));
		}
		Map<String, Integer> order = Map.of("RED", 0, "AMBER", 1, "GREEN", 2);
		schools.sort(Comparator.comparing((SchoolHealth h) -> order.get(h.health())).thenComparing(SchoolHealth::name));
		Map<String, Integer> interest = Map.of("HOT", 0, "WARM", 1, "COLD", 2);
		trials.sort(Comparator.comparing((TrialRow t) -> interest.get(t.interest())).thenComparing(TrialRow::daysLeft));

		// Trials that became customers
		YearMonth month = YearMonth.from(today);
		Date monthStart = Date.valueOf(month.atDay(1));
		int trialsMonth = count("select count(*) from trial_signup where created_at >= ?", monthStart);
		int trialsAll = count("select count(*) from trial_signup");
		int convertedMonth = count("select count(*) from platform_audit_log where action = 'TRIAL_CONVERTED' and at >= ?", monthStart);
		int convertedAll = count("select count(*) from platform_audit_log where action = 'TRIAL_CONVERTED'");
		Integer rate = trialsAll == 0 ? null : Math.round(100f * convertedAll / trialsAll);

		List<GrowthPoint> growth = new ArrayList<>();
		for (int i = 11; i >= 0; i--) {
			YearMonth m = month.minusMonths(i);
			Date end = Date.valueOf(m.atEndOfMonth());
			int schoolsThen = count("select count(*) from school where created_at::date <= ?", end);
			Long studentsThen = jdbc.queryForObject("""
					select count(*) from student where admission_date <= ? and (left_date is null or left_date > ?)""", Long.class,
					end, end);
			growth.add(new GrowthPoint(m.toString(), schoolsThen, studentsThen == null ? 0 : studentsThen));
		}

		long allStudents = students.values().stream().mapToLong(Long::longValue).sum();
		long activeUsers = users7.values().stream().mapToLong(Long::longValue).sum();
		Headline headline = new Headline(schools.size(), paying, onTrial, endingSoon, money.pausedSchools(), allStudents,
				activeUsers, money.expectedThisMonth(), money.receivedThisMonth(), money.overdue());
		return new BusinessDashboard(headline, new Conversion(trialsMonth, convertedMonth, trialsAll, convertedAll, rate),
				trials, schools, growth);
	}

	private static String worse(String current, String other) {
		Function<String, Integer> rank = h -> switch (h) {
			case "RED" -> 0;
			case "AMBER" -> 1;
			default -> 2;
		};
		return rank.apply(other) < rank.apply(current) ? other : current;
	}

	private Map<Long, Long> counts(String sql, Object... args) {
		Map<Long, Long> out = new HashMap<>();
		jdbc.query(sql, rs -> {
			out.put(rs.getLong(1), rs.getLong(2));
		}, args);
		return out;
	}

	private int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}
}
