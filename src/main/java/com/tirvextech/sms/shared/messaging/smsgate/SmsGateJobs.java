package com.selectcode.sms.shared.messaging.smsgate;

import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background checks for schools that send SMS through their own phone:
 * <ul>
 * <li>a message with the phone for 30 minutes without a report is looked up; after 24 hours it counts as failed;</li>
 * <li>a phone silent for 30 minutes while messages wait raises one email alert (not an SMS: that is what is down).</li>
 * </ul>
 */
@Component
public class SmsGateJobs {

	private static final Logger log = LoggerFactory.getLogger(SmsGateJobs.class);

	static final Duration CHECK_AFTER = Duration.ofMinutes(30);

	static final Duration CHECK_WITHOUT_REPORTS_AFTER = Duration.ofMinutes(1);

	static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

	private final MessageOutbox outbox;

	private final SmsGateways gateways;

	private final SmsGateClient client;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final boolean enabled;

	SmsGateJobs(MessageOutbox outbox, SmsGateways gateways, SmsGateClient client, JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager, Clock clock,
			@Value("${app.outbox.auto-dispatch:true}") boolean enabled) {
		this.outbox = outbox;
		this.gateways = gateways;
		this.client = client;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${app.sms-gate.check-delay-ms:60000}", initialDelay = 60000)
	void scheduled() {
		if (enabled) {
			checkStuck();
			alertOffline();
		}
	}

	/** @return messages whose state changed */
	public int checkStuck() {
		int changed = 0;
		// Phones that send delivery reports are asked only about messages silent for 30 minutes; phones that cannot
		// (no public https address yet) are asked about every message after a minute.
		for (MessageOutbox.Stuck s : outbox.stuck(CHECK_WITHOUT_REPORTS_AFTER, 50)) {
			Optional<SmsGateways.Gateway> g = TenantContext.callAsSchool(s.schoolId(),
					() -> gateways.forSchool(s.schoolId()).filter(SmsGateways.Gateway::usable));
			if (g.isPresent() && g.get().webhooksRegisteredAt() != null && g.get().lastSeenAt() != null
					&& s.queuedAt().isAfter(clock.instant().minus(CHECK_AFTER))) {
				continue;
			}
			boolean tooOld = s.queuedAt().isBefore(clock.instant().minus(GIVE_UP_AFTER));
			String state = null;
			String reason = null;
			if (g.isPresent()) {
				try {
					SmsGateClient.MessageState st = client.state(g.get().connection(), s.gatewayId());
					state = st.state();
					reason = st.reason();
				}
				catch (SmsGateClient.GatewayException e) {
					log.warn("Could not look up message {}: {}", s.id(), e.getMessage());
				}
			}
			String finalState = state;
			String finalReason = reason;
			boolean moved = Boolean.TRUE.equals(TenantContext.callAsSchool(s.schoolId(),
					() -> transaction.execute(tx -> {
						boolean m = switch (finalState == null ? "" : finalState) {
							case "Sent" -> outbox.applyReport(s.gatewayId(), "sms:sent", null, null);
							case "Delivered" -> outbox.applyReport(s.gatewayId(), "sms:delivered", null, null);
							case "Failed" -> outbox.applyReport(s.gatewayId(), "sms:failed", finalReason, null);
							case "Cancelled" -> outbox.applyReport(s.gatewayId(), "sms:cancelled", null, null);
							default -> false;
						};
						if ("Sent".equals(finalState) || "Delivered".equals(finalState)) {
							gateways.seen(s.schoolId(), null, null, null); // the phone did its job, so it is on
						}
						if (!m && tooOld) {
							outbox.failStuck(s.id(), "NotFound".equals(finalState) ? "The phone never received it"
									: "No report from the phone within 24 hours");
							m = true;
						}
						outbox.markChecked(s.id());
						return m;
					})));
			if (moved) {
				changed++;
			}
		}
		return changed;
	}

	/** @return schools alerted */
	public int alertOffline() {
		int alerted = 0;
		Instant now = clock.instant();
		for (SmsGateways.Gateway g : gateways.allEnabled()) {
			if (gateways.online(g, now) || g.offlineAlertedAt() != null
					|| g.connectedAt().isAfter(now.minus(CHECK_AFTER))) {
				continue;
			}
			Boolean sent = TenantContext.callAsSchool(g.schoolId(), () -> transaction.execute(tx -> {
				int waiting = outbox.waiting();
				if (waiting == 0) {
					return false;
				}
				Set<String> to = new LinkedHashSet<>();
				if (g.alertEmail() != null && !g.alertEmail().isBlank()) {
					to.add(g.alertEmail().trim());
				}
				jdbc.queryForList("select email from school_profile where school_id = ? and email is not null",
						String.class, g.schoolId()).forEach(e -> to.add(e.trim()));
				String school = jdbc.queryForObject("select name from school where id = ?", String.class, g.schoolId());
				String last = g.lastSeenAt() == null ? "never"
						: DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(g.lastSeenAt().atZone(MessageOutbox.ZONE));
				String body = """
						The SMS phone of %s has not been in touch since %s, and %d message(s) are waiting.

						Please check the phone: it should be switched on, charging, connected to the internet, and the SMS Gate app's service should be running.
						Waiting messages are sent automatically once the phone is back.""".formatted(school, last, waiting);
				for (String email : to) {
					outbox.queueEmail(email, "SMS phone offline: " + school, body, "SYSTEM_ALERT", null);
				}
				gateways.markOfflineAlerted(g.schoolId());
				return true;
			}));
			if (Boolean.TRUE.equals(sent)) {
				alerted++;
			}
		}
		return alerted;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Karachi")
	void pruneEvents() {
		TenantContext.callAcrossAllSchools(() -> jdbc.update("delete from sms_gateway_event where received_at < now() - interval '7 days'"));
	}
}
