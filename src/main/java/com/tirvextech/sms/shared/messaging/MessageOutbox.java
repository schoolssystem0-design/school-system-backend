package com.tirvextech.sms.shared.messaging;

import com.tirvextech.sms.shared.messaging.smsgate.SmsGateClient;
import com.tirvextech.sms.shared.messaging.smsgate.SmsGateways;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox for SMS and email to families. A message is written in the same transaction as the business
 * change, so it exists only if that change commits; it is sent afterwards and retried with back-off (QA-FEE-015).
 *
 * <p>SMS for a school that connected its own phone (SMS Gate) go to that phone: most urgent first, never during the
 * school's quiet hours, at most the daily limit, and never after they expire. The phone then reports SENT, DELIVERED or
 * FAILED by webhook. Other SMS use the configured bulk-SMS provider, as before.
 */
@Component
public class MessageOutbox {

	private static final Logger log = LoggerFactory.getLogger(MessageOutbox.class);

	public static final int MAX_ATTEMPTS = 5;

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	/** Purposes that skip quiet hours and the daily limit (a test SMS the admin is waiting for). */
	private static final Set<String> IMMEDIATE = Set.of("TEST");

	private static final int MAX_PER_RUN = 100;

	public record Queued(long id, String channel, String recipient, String body, String purpose, String sourceType,
			Long sourceId, String status, int attempts, String lastError, Instant createdAt, Instant sentAt,
			Instant deliveredAt, boolean viaPhone) {
	}

	private final JdbcTemplate jdbc;

	private final MessageSender sender;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final boolean autoDispatch;

	private final SmsGateways gateways;

	private final SmsGateClient gateClient;

	private final Set<String> allowList;

	private final SecureRandom random = new SecureRandom();

	MessageOutbox(JdbcTemplate jdbc, MessageSender sender, PlatformTransactionManager transactionManager, Clock clock,
			@Value("${app.outbox.auto-dispatch:true}") boolean autoDispatch, SmsGateways gateways,
			SmsGateClient gateClient, @Value("${app.messaging.allow-list:}") String allowList) {
		this.jdbc = jdbc;
		this.sender = sender;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.autoDispatch = autoDispatch;
		this.gateways = gateways;
		this.gateClient = gateClient;
		this.allowList = Arrays.stream(allowList.split(",")).map(String::trim).filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}

	/** 1 = most urgent. Absence alerts jump the queue; birthday wishes wait for everything else. */
	static int priority(String purpose) {
		return switch (purpose) {
			case "TEST" -> 0;
			case "ATTENDANCE_ABSENT", "SYSTEM_ALERT" -> 1;
			case "BIRTHDAY" -> 9;
			default -> 5;
		};
	}

	/**
	 * Queues an SMS in the caller's transaction. With an {@code idempotencyKey}, the same key and recipient are queued
	 * only once (a retried "send reminders" request); a message cancelled earlier under the key is queued again.
	 *
	 * @return true if queued (false = already queued under this key)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean queueSms(String phone, String body, String purpose, String sourceType, Long sourceId,
			String idempotencyKey) {
		return queueSms(phone, body, purpose, sourceType, sourceId, idempotencyKey, null, null);
	}

	/**
	 * @param notBefore send no earlier than this (null = now)
	 * @param expiresAt cancel instead of sending after this (null = never)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean queueSms(String phone, String body, String purpose, String sourceType, Long sourceId,
			String idempotencyKey, Instant notBefore, Instant expiresAt) {
		return queue("SMS", phone, null, body, purpose, sourceType, sourceId, idempotencyKey, notBefore, expiresAt);
	}

	/** Queues an email (system alerts). */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean queueEmail(String to, String subject, String body, String purpose, String idempotencyKey) {
		return queue("EMAIL", to, subject, body, purpose, null, null, idempotencyKey, null, null);
	}

	private boolean queue(String channel, String recipient, String subject, String body, String purpose,
			String sourceType, Long sourceId, String idempotencyKey, Instant notBefore, Instant expiresAt) {
		Instant now = clock.instant();
		int rows = jdbc.update("""
				insert into message_outbox (school_id, channel, recipient, subject, body, purpose, source_type, source_id,
				                            idempotency_key, priority, next_attempt_at, expires_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (school_id, idempotency_key, recipient) where idempotency_key is not null
				do update set status = 'PENDING', attempts = 0, last_error = null, body = excluded.body,
				              next_attempt_at = excluded.next_attempt_at, expires_at = excluded.expires_at,
				              gateway_id = null, queued_at = null, sent_at = null, delivered_at = null
				where message_outbox.status = 'CANCELLED'""", TenantContext.requireSchoolId(), channel, recipient, subject,
				body, purpose, sourceType, sourceId, idempotencyKey, priority(purpose),
				Timestamp.from(notBefore == null || notBefore.isBefore(now) ? now : notBefore),
				expiresAt == null ? null : Timestamp.from(expiresAt));
		return rows > 0;
	}

	@Transactional(readOnly = true)
	public List<Queued> list(String purposePrefix, String status, int limit) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				select id, channel, recipient, body, purpose, source_type, source_id, status, attempts, last_error,
				       created_at, sent_at, delivered_at, queued_at
				from message_outbox where 1 = 1""");
		if (purposePrefix != null && !purposePrefix.isBlank()) {
			sql.append(" and purpose like ?");
			args.add(purposePrefix + "%");
		}
		if (status != null && !status.isBlank()) {
			sql.append(" and status = ?");
			args.add(status);
		}
		sql.append(" order by id desc limit ?");
		args.add(Math.min(Math.max(limit, 1), 500));
		return jdbc.query(sql.toString(), (rs, i) -> new Queued(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getString(4), rs.getString(5), rs.getString(6), (Long) rs.getObject(7), rs.getString(8),
				rs.getInt(9), rs.getString(10), rs.getTimestamp(11).toInstant(), instant(rs.getTimestamp(12)),
				instant(rs.getTimestamp(13)), rs.getTimestamp(14) != null), args.toArray());
	}

	/** Sends a FAILED message again, with a fresh set of tries. @return false if it is not a failed message of this school */
	@Transactional
	public boolean retry(long id) {
		return jdbc.update("""
				update message_outbox set status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = null,
				       gateway_id = null, queued_at = null, status_checked_at = null
				where id = ? and status = 'FAILED'""", Timestamp.from(clock.instant()), id) > 0;
	}

	public record StatusCount(String status, int count) {
	}

	/** Messages by status since a time, for the delivery log. */
	public List<StatusCount> statusCounts(Instant since) {
		return jdbc.query("select status, count(*) from message_outbox where created_at >= ? group by status order by status",
				(rs, i) -> new StatusCount(rs.getString(1), rs.getInt(2)), Timestamp.from(since));
	}

	/** Start of today in Pakistan time. */
	public Instant startOfToday() {
		return LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toInstant();
	}

	// ------------------------------------------------------------------ cancel

	/**
	 * Cancels the current school's not-yet-sent messages queued under a key (an absence corrected to present). Messages
	 * already with the phone are cancelled there too, after this transaction commits; one the phone already sent stays
	 * SENT when its report arrives.
	 *
	 * @return number of messages cancelled
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int cancel(String idempotencyKey, String reason) {
		long schoolId = TenantContext.requireSchoolId();
		List<String> atPhone = jdbc.queryForList("""
				select gateway_id from message_outbox
				where idempotency_key = ? and status = 'QUEUED' and gateway_id is not null""", String.class, idempotencyKey);
		int rows = jdbc.update("""
				update message_outbox set status = 'CANCELLED', last_error = ?
				where idempotency_key = ? and status in ('PENDING', 'QUEUED')""", reason, idempotencyKey);
		if (!atPhone.isEmpty()) {
			org.springframework.transaction.support.TransactionSynchronizationManager
				.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
					@Override
					public void afterCommit() {
						gateways.forSchool(schoolId).filter(SmsGateways.Gateway::usable).ifPresent(g -> {
							for (String id : atPhone) {
								try {
									gateClient.cancel(g.connection(), id);
								}
								catch (RuntimeException e) {
									log.warn("Could not cancel message {} on the phone: {}", id, e.getMessage());
								}
							}
						});
					}
				});
		}
		return rows;
	}

	// ---------------------------------------------------------------- dispatch

	@Scheduled(fixedDelayString = "${app.outbox.dispatch-delay-ms:10000}", initialDelay = 20000)
	void scheduledDispatch() {
		if (autoDispatch) {
			dispatchDue();
		}
	}

	private record Row(long id, long schoolId, String channel, String recipient, String subject, String body,
			String purpose, int attempts, String mask, String gatewayId, Instant expiresAt) {
	}

	private enum Outcome {
		HANDED_OVER, DEFERRED, FAILED_TRY, CANCELLED
	}

	/**
	 * Sends messages that are due, across all schools, one per short transaction (a slow phone never holds other rows).
	 *
	 * @return number of messages handed to a provider or a phone
	 */
	public int dispatchDue() {
		int ok = 0;
		Map<Long, Optional<SmsGateways.Gateway>> gatewayCache = new HashMap<>();
		Map<Long, Integer> sentToday = new HashMap<>();
		for (int i = 0; i < MAX_PER_RUN; i++) {
			Outcome outcome = TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
				Instant now = clock.instant();
				List<Row> due = jdbc.query("""
						select o.id, o.school_id, o.channel, o.recipient, o.subject, o.body, o.purpose, o.attempts,
						       (select v.value #>> '{}' from setting_value v where v.school_id = o.school_id
						          and v.setting_key = 'messages.sms_sender_mask' order by v.id desc limit 1),
						       o.gateway_id, o.expires_at
						from message_outbox o
						where o.status = 'PENDING' and o.next_attempt_at <= ?
						order by o.priority, o.next_attempt_at, o.id limit 1 for update of o skip locked""",
						(rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
								rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9),
								rs.getString(10), instant(rs.getTimestamp(11))),
						Timestamp.from(now));
				if (due.isEmpty()) {
					return null;
				}
				Row row = due.getFirst();
				if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
					jdbc.update("update message_outbox set status = 'CANCELLED', last_error = ? where id = ?",
							"Expired before it could be sent", row.id());
					return Outcome.CANCELLED;
				}
				Optional<SmsGateways.Gateway> gateway = "SMS".equals(row.channel())
						? gatewayCache.computeIfAbsent(row.schoolId(), id -> gateways.forSchool(id).filter(SmsGateways.Gateway::usable))
						: Optional.empty();
				if (gateway.isPresent()) {
					return viaPhone(row, gateway.get(), now, sentToday);
				}
				return viaProvider(row, now);
			}));
			if (outcome == null) {
				break;
			}
			if (outcome == Outcome.HANDED_OVER) {
				ok++;
			}
		}
		return ok;
	}

	private Outcome viaProvider(Row row, Instant now) {
		OutgoingMessage.Channel channel = OutgoingMessage.Channel.valueOf(row.channel());
		String error = null;
		try {
			if (!sender.supports(channel)) {
				error = "No " + channel + " provider configured";
			}
			else if (!sender.send(new OutgoingMessage(channel, row.recipient(), row.subject(), row.body(), row.mask()))) {
				error = "Provider did not accept the message";
			}
		}
		catch (RuntimeException e) {
			error = e.getClass().getSimpleName();
		}
		int attempts = row.attempts() + 1;
		if (error == null) {
			jdbc.update("update message_outbox set status = 'SENT', attempts = ?, sent_at = ?, last_error = null "
					+ "where id = ?", attempts, Timestamp.from(now), row.id());
			return Outcome.HANDED_OVER;
		}
		backOff(row, attempts, error, now, true);
		return Outcome.FAILED_TRY;
	}

	private Outcome viaPhone(Row row, SmsGateways.Gateway g, Instant now, Map<Long, Integer> sentToday) {
		boolean immediate = IMMEDIATE.contains(row.purpose());
		if (!immediate) {
			Instant quietEnd = quietHoursEnd(row.schoolId(), now);
			if (quietEnd != null) {
				jdbc.update("update message_outbox set next_attempt_at = ? where id = ?", Timestamp.from(quietEnd), row.id());
				return Outcome.DEFERRED;
			}
			int limit = intSetting(row.schoolId(), "messages.sms_daily_limit", 200);
			int today = sentToday.computeIfAbsent(row.schoolId(), id -> jdbc.queryForObject(
					"select count(*) from message_outbox where school_id = ? and queued_at >= ?", Integer.class, id,
					Timestamp.from(startOfToday())));
			if (limit > 0 && today >= limit) {
				Instant tomorrow = LocalDate.now(clock.withZone(ZONE)).plusDays(1).atStartOfDay(ZONE).toInstant();
				jdbc.update("update message_outbox set next_attempt_at = ?, last_error = ? where id = ?",
						Timestamp.from(tomorrow), "Daily SMS limit reached; waiting for tomorrow", row.id());
				return Outcome.DEFERRED;
			}
		}
		if (!allowList.isEmpty() && !allowList.contains(row.recipient())) {
			jdbc.update("update message_outbox set status = 'FAILED', attempts = attempts + 1, last_error = ? where id = ?",
					"Not on the allow-list of this test server", row.id());
			return Outcome.FAILED_TRY;
		}
		String phone = international(row.recipient());
		if (phone == null) {
			jdbc.update("update message_outbox set status = 'FAILED', attempts = attempts + 1, last_error = ? where id = ?",
					"Not a mobile number: " + row.recipient(), row.id());
			return Outcome.FAILED_TRY;
		}
		// Our id goes with the message, so if this hand-over is repeated the phone still sends it only once.
		String gatewayId = row.gatewayId() != null ? row.gatewayId()
				: "sg-" + row.id() + "-" + HexFormat.of().formatHex(randomBytes());
		if (row.gatewayId() == null) {
			jdbc.update("update message_outbox set gateway_id = ? where id = ?", gatewayId, row.id());
		}
		Long ttl = row.expiresAt() == null ? null : Duration.between(now, row.expiresAt()).toSeconds();
		int attempts = row.attempts() + 1;
		try {
			gateClient.send(g.connection(), gatewayId, phone, row.body(), ttl, g.simNumber(), immediate);
			jdbc.update("""
					update message_outbox set status = 'QUEUED', attempts = ?, queued_at = ?, last_error = null
					where id = ? and status = 'PENDING'""", attempts, Timestamp.from(now), row.id());
			sentToday.merge(row.schoolId(), 1, Integer::sum);
			return Outcome.HANDED_OVER;
		}
		catch (SmsGateClient.GatewayException e) {
			backOff(row, attempts, e.getMessage(), now, e.retryable());
			return Outcome.FAILED_TRY;
		}
	}

	private void backOff(Row row, int attempts, String error, Instant now, boolean retryable) {
		// Back-off: 1, 2, 4, 8 minutes; then give up and leave it visible as FAILED.
		String status = !retryable || attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
		Instant next = now.plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 6)));
		jdbc.update("update message_outbox set status = ?, attempts = ?, last_error = ?, next_attempt_at = ? where id = ?",
				status, attempts, error, Timestamp.from(next), row.id());
		log.warn("Message {} not sent (attempt {}): {}", row.id(), attempts, error);
	}

	/** If it is quiet time for the school now, when quiet time ends; otherwise null. Equal start and end = no quiet hours. */
	Instant quietHoursEnd(long schoolId, Instant now) {
		LocalTime start = timeSetting(schoolId, "messages.sms_quiet_start", LocalTime.of(21, 0));
		LocalTime end = timeSetting(schoolId, "messages.sms_quiet_end", LocalTime.of(7, 0));
		if (start.equals(end)) {
			return null;
		}
		ZonedDateTime local = now.atZone(ZONE);
		LocalTime t = local.toLocalTime();
		boolean quiet = start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
		if (!quiet) {
			return null;
		}
		ZonedDateTime endToday = local.toLocalDate().atTime(end).atZone(ZONE);
		return (endToday.isAfter(local) ? endToday : endToday.plusDays(1)).toInstant();
	}

	private String setting(long schoolId, String key) {
		return jdbc.queryForList("""
				select v.value #>> '{}' from setting_value v where v.school_id = ? and v.setting_key = ?
				order by v.id desc limit 1""", String.class, schoolId, key).stream().findFirst().orElse(null);
	}

	private int intSetting(long schoolId, String key, int fallback) {
		try {
			String v = setting(schoolId, key);
			return v == null ? fallback : Integer.parseInt(v.trim());
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}

	private LocalTime timeSetting(long schoolId, String key, LocalTime fallback) {
		try {
			String v = setting(schoolId, key);
			return v == null ? fallback : LocalTime.parse(v.trim());
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	/** "03001234567" → "+923001234567". Only Pakistani mobile numbers (or numbers already in +country form). */
	public static String international(String phone) {
		if (phone == null) {
			return null;
		}
		String raw = phone.trim();
		String digits = raw.replaceAll("\\D", "");
		if (raw.startsWith("+") && digits.length() >= 10 && digits.length() <= 15) {
			return "+" + digits;
		}
		if (digits.startsWith("0092")) {
			digits = digits.substring(2);
		}
		else if (digits.startsWith("0")) {
			digits = "92" + digits.substring(1);
		}
		return digits.matches("923\\d{9}") ? "+" + digits : null;
	}

	// ------------------------------------------------------- reports from the phone

	/**
	 * Applies a delivery report from the phone. States only move forward: a late "sent" never undoes "delivered", and a
	 * message the phone really sent is shown as sent even if it was cancelled here meanwhile.
	 *
	 * @return true if a message changed
	 */
	public boolean applyReport(String gatewayId, String event, String reason, Instant at) {
		Timestamp when = Timestamp.from(at == null ? clock.instant() : at);
		return switch (event) {
			case "sms:sent" -> jdbc.update("""
					update message_outbox set status = 'SENT', sent_at = coalesce(sent_at, ?), last_error = null
					where gateway_id = ? and status in ('PENDING', 'QUEUED', 'CANCELLED')""", when, gatewayId) > 0;
			case "sms:delivered" -> jdbc.update("""
					update message_outbox set status = 'DELIVERED', delivered_at = coalesce(delivered_at, ?),
					       sent_at = coalesce(sent_at, ?), last_error = null
					where gateway_id = ? and status <> 'DELIVERED'""", when, when, gatewayId) > 0;
			case "sms:failed" -> jdbc.update("""
					update message_outbox set status = 'FAILED', last_error = ?
					where gateway_id = ? and status in ('PENDING', 'QUEUED', 'SENT')""",
					reason == null || reason.isBlank() ? "The phone could not send it" : trim(reason, 500), gatewayId) > 0;
			case "sms:cancelled" -> jdbc.update("""
					update message_outbox set status = 'CANCELLED', last_error = coalesce(last_error, 'Cancelled on the phone')
					where gateway_id = ? and status in ('PENDING', 'QUEUED')""", gatewayId) > 0;
			default -> false;
		};
	}

	public record Stuck(long id, long schoolId, String gatewayId, Instant queuedAt) {
	}

	/** Messages with the phone for a while without any report, across all schools. */
	public List<Stuck> stuck(Duration olderThan, int limit) {
		Instant cutoff = clock.instant().minus(olderThan);
		return TenantContext.callAcrossAllSchools(() -> jdbc.query("""
				select id, school_id, gateway_id, queued_at from message_outbox
				where status = 'QUEUED' and queued_at < ? and (status_checked_at is null or status_checked_at < ?)
				order by queued_at limit ?""", (rs, i) -> new Stuck(rs.getLong(1), rs.getLong(2), rs.getString(3),
				rs.getTimestamp(4).toInstant()), Timestamp.from(cutoff), Timestamp.from(cutoff), limit));
	}

	public void markChecked(long id) {
		jdbc.update("update message_outbox set status_checked_at = ? where id = ?", Timestamp.from(clock.instant()), id);
	}

	public void failStuck(long id, String reason) {
		jdbc.update("update message_outbox set status = 'FAILED', last_error = ? where id = ? and status = 'QUEUED'", reason,
				id);
	}

	/** Messages still waiting (not yet with the phone, or with it but not sent) for the current school. */
	public int waiting() {
		Integer n = jdbc.queryForObject("select count(*) from message_outbox where status in ('PENDING', 'QUEUED')",
				Integer.class);
		return n == null ? 0 : n;
	}

	private byte[] randomBytes() {
		byte[] b = new byte[4];
		random.nextBytes(b);
		return b;
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}
}
