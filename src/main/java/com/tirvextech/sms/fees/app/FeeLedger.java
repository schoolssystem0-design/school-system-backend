package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.accounting.JournalWriter;
import com.selectcode.sms.shared.events.SchoolCreatedEvent;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ledger accounts and journal posting for fees (DATA-MODEL §8). Phase 4 completes school accounts; fees already post
 * balanced journal entries so the accounts start from correct figures: a payment debits cash/bank and credits fee
 * income (allocated part) and fees received in advance (unallocated part); applying credit later moves it from
 * advances to fee income.
 */
@Component
public class FeeLedger {

	public static final String CASH = "CASH";

	public static final String BANK = "BANK";

	public static final String FEE_INCOME = "FEE_INCOME";

	public static final String STUDENT_ADVANCE = "STUDENT_ADVANCE";

	public record AccountView(long id, String code, String name, String subtype) {
	}

	public record Line(long accountId, BigDecimal debit, BigDecimal credit) {

		public static Line debit(long accountId, BigDecimal amount) {
			return new Line(accountId, amount, BigDecimal.ZERO);
		}

		public static Line credit(long accountId, BigDecimal amount) {
			return new Line(accountId, BigDecimal.ZERO, amount);
		}
	}

	private final JdbcTemplate jdbc;

	private final JournalWriter journal;

	FeeLedger(JdbcTemplate jdbc, JournalWriter journal) {
		this.jdbc = jdbc;
		this.journal = journal;
	}

	/** New schools get the standard accounts and the system "Late fee" head (same transaction as their creation). */
	@EventListener
	public void onSchoolCreated(SchoolCreatedEvent event) {
		Object[][] accounts = { { "1000", "Cash in hand", "ASSET", CASH, 1 }, { "1010", "Bank account", "ASSET", BANK, 2 },
				{ "2100", "Fees received in advance", "LIABILITY", STUDENT_ADVANCE, 3 },
				{ "4000", "Fee income", "INCOME", FEE_INCOME, 4 } };
		for (Object[] a : accounts) {
			jdbc.update("""
					insert into ledger_account (school_id, code, name, account_type, subtype, display_order)
					values (?, ?, ?, ?, ?, ?)""", event.schoolId(), a[0], a[1], a[2], a[3], a[4]);
		}
		jdbc.update("""
				insert into fee_head (school_id, name, frequency, display_order, system_code)
				values (?, 'Late fee', 'CUSTOM', 1000, 'LATE_FEE'), (?, 'Absent fine', 'CUSTOM', 1001, 'ABSENT_FINE')""",
				event.schoolId(), event.schoolId());
	}

	/** Accounts money can be received into (cash in hand, bank accounts). */
	@Transactional(readOnly = true)
	public List<AccountView> receivingAccounts() {
		return jdbc.query("""
				select id, code, name, subtype from ledger_account
				where subtype in ('CASH', 'BANK') and status = 'ACTIVE' order by display_order, code""",
				(rs, i) -> new AccountView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
	}

	public AccountView requireReceivingAccount(long id) {
		return receivingAccounts().stream()
			.filter(a -> a.id() == id)
			.findFirst()
			.orElseThrow(() -> ApiException.businessRule("ACCOUNT_INVALID", "Choose a cash or bank account"));
	}

	long accountOf(String subtype) {
		return journal.accountOf(subtype);
	}

	/** Posts a balanced journal entry through the shared journal (balance and day-close checks). */
	@Transactional(propagation = Propagation.MANDATORY)
	public long post(LocalDate date, String type, String sourceType, Long sourceId, String memo, Long reversedEntryId,
			List<Line> lines) {
		return journal.post(date, type, sourceType, sourceId, memo, reversedEntryId,
				lines.stream().map(l -> new JournalWriter.Line(l.accountId(), l.debit(), l.credit())).toList());
	}

	/**
	 * Serialises money changes for one family (payments, bill runs, credit, adjustments) for the rest of the
	 * transaction, so two cashiers can never allocate the same balance twice (QA-FEE-013). Advisory locks need no
	 * table privileges, which keeps the money tables insert-only.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockFamily(long familyId) {
		jdbc.queryForList("select pg_advisory_xact_lock(7301, ?)", Object.class, (int) familyId);
	}

	/** Serialises requests that carry the same idempotency key. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockKey(String scope, String key) {
		jdbc.queryForList("select pg_advisory_xact_lock(7302, hashtext(?))",
				Object.class, TenantContext.requireSchoolId() + ":" + scope + ":" + key);
	}
}
