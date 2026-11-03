package com.selectcode.sms.shared.accounting;

import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money reaches the books (DATA-MODEL §8). Fees, accounts and payroll all post through here, so every
 * entry is balanced (also checked by the database at commit) and no cash or bank account can receive an entry dated
 * on or before a day that was closed for it (FR 11.9).
 */
@Component
public class JournalWriter {

	public record Line(long accountId, BigDecimal debit, BigDecimal credit) {

		public static Line debit(long accountId, BigDecimal amount) {
			return new Line(accountId, amount, BigDecimal.ZERO);
		}

		public static Line credit(long accountId, BigDecimal amount) {
			return new Line(accountId, BigDecimal.ZERO, amount);
		}
	}

	private final JdbcTemplate jdbc;

	JournalWriter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The first active ledger account of a kind, e.g. {@code FEE_INCOME} or {@code SALARY_PAYABLE}. */
	public long accountOf(String subtype) {
		List<Long> ids = jdbc.queryForList(
				"select id from ledger_account where subtype = ? and status = 'ACTIVE' order by display_order, id limit 1",
				Long.class, subtype);
		if (ids.isEmpty()) {
			throw new IllegalStateException("No active " + subtype + " ledger account");
		}
		return ids.getFirst();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public long post(LocalDate date, String type, String sourceType, Long sourceId, String memo, Long reversedEntryId,
			List<Line> lines) {
		BigDecimal debit = BigDecimal.ZERO;
		BigDecimal credit = BigDecimal.ZERO;
		for (Line line : lines) {
			if (line.debit().signum() < 0 || line.credit().signum() < 0) {
				throw new IllegalStateException("Negative journal amount");
			}
			debit = debit.add(line.debit());
			credit = credit.add(line.credit());
		}
		if (debit.compareTo(credit) != 0) {
			throw new IllegalStateException("Unbalanced journal entry: " + debit + " / " + credit);
		}
		lockCashAndBank(lines.stream().map(Line::accountId).toList());
		for (Line line : lines) {
			if (line.debit().signum() == 0 && line.credit().signum() == 0) {
				continue;
			}
			List<String> closed = jdbc.queryForList("""
					select a.name from day_close d join ledger_account a on a.id = d.ledger_account_id
					where d.ledger_account_id = ? and d.close_date >= ? limit 1""", String.class, line.accountId(),
					Date.valueOf(date));
			if (!closed.isEmpty()) {
				throw ApiException.conflict("DAY_CLOSED",
						"The day is already closed for " + closed.getFirst() + ". Use a later date.");
			}
		}
		long schoolId = TenantContext.requireSchoolId();
		Long id = jdbc.queryForObject("""
				insert into journal_entry (school_id, entry_date, entry_type, source_type, source_id, memo,
				                           reversed_entry_id, created_by)
				values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, schoolId, Date.valueOf(date), type,
				sourceType, sourceId, memo, reversedEntryId,
				CurrentActor.get().map(a -> a.displayName()).orElse("System"));
		for (Line line : lines) {
			if (line.debit().signum() == 0 && line.credit().signum() == 0) {
				continue;
			}
			jdbc.update("""
					insert into journal_line (school_id, entry_id, ledger_account_id, debit, credit)
					values (?, ?, ?, ?, ?)""", schoolId, id, line.accountId(), line.debit(), line.credit());
		}
		return id;
	}

	/**
	 * Locks cash and bank accounts (in id order, so no deadlocks) for the rest of the transaction. A day close takes the
	 * same lock, so a posting and the close of that day can never interleave.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockCashAndBank(java.util.Collection<Long> accountIds) {
		if (accountIds.isEmpty()) {
			return;
		}
		Long[] ids = accountIds.stream().distinct().toArray(Long[]::new);
		List<Long> money = jdbc.queryForList("""
				select id from ledger_account where id = any(?) and subtype in ('CASH', 'BANK') order by id""",
				Long.class, (Object) ids);
		for (long id : money) {
			jdbc.queryForList("select pg_advisory_xact_lock(7303, ?)", Object.class, (int) id);
		}
	}

	/**
	 * Posts the mirror image of an entry (debits become credits) dated {@code date}, linked to the original.
	 *
	 * @return the reversal entry id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public long reverse(long entryId, LocalDate date, String sourceType, Long sourceId, String memo) {
		List<Line> mirror = jdbc.query("select ledger_account_id, debit, credit from journal_line where entry_id = ?",
				(rs, i) -> new Line(rs.getLong(1), rs.getBigDecimal(3), rs.getBigDecimal(2)), entryId);
		if (mirror.isEmpty()) {
			throw new IllegalStateException("Journal entry " + entryId + " has no lines");
		}
		return post(date, "REVERSAL", sourceType, sourceId, memo, entryId, mirror);
	}
}
