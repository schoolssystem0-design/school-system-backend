package com.selectcode.sms.shared.messaging;

import com.selectcode.sms.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Transactional outbox for SMS and email to families (receipts, fee reminders). A message is written in the same
 * transaction as the business change, so it exists only if that change commits; it is sent afterwards and retried
 * with back-off when the provider is down (QA-FEE-015). Phase 7 adds templates and more channels on top.
 */
@Component
public class MessageOutbox {

	private static final Logger log = LoggerFactory.getLogger(MessageOutbox.class);

	public static final int MAX_ATTEMPTS = 5;

	public record Queued(long id, String channel, String recipient, String body, String purpose, String sourceType,
			Long sourceId, String status, int attempts, String lastError, Instant createdAt, Instant sentAt) {
	}

	private final JdbcTemplate jdbc;

	private final MessageSender sender;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final boolean autoDispatch;

	MessageOutbox(JdbcTemplate jdbc, MessageSender sender, PlatformTransactionManager transactionManager, Clock clock,
			@Value("${app.outbox.auto-dispatch:true}") boolean autoDispatch) {
		this.jdbc = jdbc;
		this.sender = sender;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.autoDispatch = autoDispatch;
	}

	/**
	 * Queues an SMS in the caller's transaction. With an {@code idempotencyKey}, the same key and recipient are queued
	 * only once (a retried "send reminders" request).
	 *
	 * @return true if queued (false = already queued under this key)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean queueSms(String phone, String body, String purpose, String sourceType, Long sourceId,
			String idempotencyKey) {
		int rows = jdbc.update("""
				insert into message_outbox (school_id, channel, recipient, body, purpose, source_type, source_id,
				                            idempotency_key)
				values (?, 'SMS', ?, ?, ?, ?, ?, ?)
				on conflict do nothing""", TenantContext.requireSchoolId(), phone, body, purpose, sourceType, sourceId,
				idempotencyKey);
		return rows > 0;
	}

	@Transactional(readOnly = true)
	public List<Queued> list(String purposePrefix, String status, int limit) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				select id, channel, recipient, body, purpose, source_type, source_id, status, attempts, last_error,
				       created_at, sent_at
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
				rs.getInt(9), rs.getString(10), rs.getTimestamp(11).toInstant(),
				rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()), args.toArray());
	}

	/** Sends a FAILED message again, with a fresh set of tries. @return false if it is not a failed message of this school */
	@Transactional
	public boolean retry(long id) {
		return jdbc.update("update message_outbox set status = 'PENDING', attempts = 0, next_attempt_at = ? "
				+ "where id = ? and status = 'FAILED'", Timestamp.from(clock.instant()), id) > 0;
	}

	public record StatusCount(String status, int count) {
	}

	/** Messages by status since a time, for the delivery log. */
	public List<StatusCount> statusCounts(Instant since) {
		return jdbc.query("select status, count(*) from message_outbox where created_at >= ? group by status order by status",
				(rs, i) -> new StatusCount(rs.getString(1), rs.getInt(2)), Timestamp.from(since));
	}

	@Scheduled(fixedDelayString = "${app.outbox.dispatch-delay-ms:30000}", initialDelay = 20000)
	void scheduledDispatch() {
		if (autoDispatch) {
			dispatchDue();
		}
	}

	/** Sends messages that are due, across all schools. @return number of messages handed to a provider */
	public int dispatchDue() {
		Instant now = clock.instant();
		Integer sent = TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			record Row(long id, String channel, String recipient, String subject, String body, int attempts,
					String mask) {
			}
			List<Row> due = jdbc.query("""
					select o.id, o.channel, o.recipient, o.subject, o.body, o.attempts,
					       (select v.value #>> '{}' from setting_value v where v.school_id = o.school_id
					          and v.setting_key = 'messages.sms_sender_mask' order by v.id desc limit 1)
					from message_outbox o
					where o.status = 'PENDING' and o.next_attempt_at <= ?
					order by o.id limit 100 for update of o skip locked""",
					(rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5), rs.getInt(6), rs.getString(7)),
					Timestamp.from(now));
			int ok = 0;
			for (Row row : due) {
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
					ok++;
				}
				else {
					// Back-off: 1, 2, 4, 8 minutes; then give up and leave it visible as FAILED.
					String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
					Instant next = now.plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 6)));
					jdbc.update("update message_outbox set status = ?, attempts = ?, last_error = ?, next_attempt_at = ? "
							+ "where id = ?", status, attempts, error, Timestamp.from(next), row.id());
					log.warn("Message {} not sent (attempt {}): {}", row.id(), attempts, error);
				}
			}
			return ok;
		}));
		return sent == null ? 0 : sent;
	}
}
