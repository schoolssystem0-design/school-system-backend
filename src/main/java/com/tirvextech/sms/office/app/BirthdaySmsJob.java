package com.tirvextech.sms.office.app;

import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatic birthday SMS: every morning, for each school that switched it on, today's birthday wishes are queued to go
 * out at the school's birthday SMS time (evening). Server time is UTC, so the schedule names the Pakistan time zone.
 */
@Component
public class BirthdaySmsJob {

	private static final Logger log = LoggerFactory.getLogger(BirthdaySmsJob.class);

	private static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private final JdbcTemplate jdbc;

	private final SettingsService settings;

	private final OfficeBoardService board;

	private final Clock clock;

	private final boolean enabled;

	BirthdaySmsJob(JdbcTemplate jdbc, SettingsService settings, OfficeBoardService board, Clock clock,
			@Value("${app.outbox.auto-dispatch:true}") boolean enabled) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.board = board;
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Karachi")
	void scheduled() {
		if (enabled) {
			run();
		}
	}

	/** @return wishes queued across all schools */
	public int run() {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		List<Long> schools = TenantContext.callAcrossAllSchools(
				() -> jdbc.queryForList("select id from school where status = 'ACTIVE' order by id", Long.class));
		int total = 0;
		for (long schoolId : schools) {
			try {
				total += TenantContext.callAsSchool(schoolId, () -> {
					if (!settings.bool(SettingKey.SEND_BIRTHDAY_SMS)) {
						return 0;
					}
					Instant sendAt = today.atTime(settings.time(SettingKey.BIRTHDAY_SMS_TIME)).atZone(ZONE).toInstant();
					return board.queueTodaysBirthdays(today, sendAt);
				});
			}
			catch (RuntimeException e) {
				log.warn("Birthday SMS for school {} not queued: {}", schoolId, e.getMessage());
			}
		}
		return total;
	}
}
