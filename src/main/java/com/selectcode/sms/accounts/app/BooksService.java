package com.selectcode.sms.accounts.app;

import com.selectcode.sms.shared.accounting.JournalWriter;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the books (FR 11.3, 11.9, 11.10, 11.12, 11.13). Every figure comes from journal lines, so fee receipts,
 * other income, expenses, transfers and salaries all appear exactly once (QA-ACC-002).
 */
@Service
public class BooksService {

	public record LedgerLine(LocalDate date, long entryId, Long lineId, String type, String memo, String debit,
			String credit, String balance, boolean cleared) {
	}

	public record AccountLedger(long accountId, String accountName, LocalDate from, LocalDate to, String opening,
			String totalIn, String totalOut, String closing, List<LedgerLine> lines) {
	}

	public record SummaryRow(String source, String amountIn, String amountOut) {
	}

	public record DaySummary(long accountId, String accountName, LocalDate date, String opening, String totalIn,
			String totalOut, String closing, List<SummaryRow> bySource, DayCloseView close) {
	}

	public record DayCloseView(long id, LocalDate date, String closing, String counted, String difference, String note,
			String closedBy, Instant closedAt) {
	}

	public record BalanceRow(long accountId, String code, String name, String type, String subtype, String debit,
			String credit, String balance) {
	}

	public record MonthRow(String month, String income, String expense, String net) {
	}

	public record CategoryRow(String code, String name, String kind, String amount) {
	}

	public record BalanceSheet(LocalDate asOf, List<BalanceRow> assets, List<BalanceRow> liabilities,
			List<BalanceRow> equity, String totalAssets, String totalLiabilities, String totalEquity,
			String surplus) {
	}

	public record ActivityRow(long id, Instant at, String action, String entityType, Long entityId, String reason,
			String actor) {
	}

	private final JdbcTemplate jdbc;

	private final AccountsSetupService setup;

	private final JournalWriter journal;

	private final AuditService audit;

	private final Clock clock;

	BooksService(JdbcTemplate jdbc, AccountsSetupService setup, JournalWriter journal, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.setup = setup;
		this.journal = journal;
		this.audit = audit;
		this.clock = clock;
	}

	// ----------------------------------------------------------------- ledger

	/**
	 * A cash or bank account's ledger (FR 11.3): opening balance, then each entry with a running balance, or totals per
	 * day or per month ({@code view} DETAILED, DAILY, MONTHLY).
	 */
	@Transactional(readOnly = true)
	public AccountLedger ledger(long accountId, LocalDate from, LocalDate to, String view) {
		var account = setup.requireMoneyAccount(accountId);
		BigDecimal opening = balanceBefore(accountId, from);
		List<Object[]> rows = jdbc.query("""
				select j.entry_date, j.id, l.id, j.entry_type, j.memo, l.debit, l.credit,
				       exists (select 1 from bank_clearance c where c.journal_line_id = l.id)
				from journal_line l join journal_entry j on j.id = l.entry_id
				where l.ledger_account_id = ? and j.entry_date between ? and ?
				order by j.entry_date, j.id, l.id""",
				(rs, i) -> new Object[] { rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getString(4),
						rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBoolean(8) },
				accountId, Date.valueOf(from), Date.valueOf(to));
		BigDecimal running = opening;
		BigDecimal in = BigDecimal.ZERO;
		BigDecimal out = BigDecimal.ZERO;
		List<LedgerLine> lines = new ArrayList<>();
		Map<String, BigDecimal[]> groups = new LinkedHashMap<>();
		for (Object[] r : rows) {
			BigDecimal debit = (BigDecimal) r[5];
			BigDecimal credit = (BigDecimal) r[6];
			in = in.add(debit);
			out = out.add(credit);
			running = running.add(debit).subtract(credit);
			LocalDate date = (LocalDate) r[0];
			if ("DETAILED".equals(view) || view == null || view.isBlank()) {
				lines.add(new LedgerLine(date, (Long) r[1], (Long) r[2], (String) r[3], (String) r[4],
						debit.signum() > 0 ? MoneyInput.text(debit) : null,
						credit.signum() > 0 ? MoneyInput.text(credit) : null, MoneyInput.text(running), (Boolean) r[7]));
			}
			else {
				String key = "MONTHLY".equals(view) ? date.toString().substring(0, 7) : date.toString();
				BigDecimal[] g = groups.computeIfAbsent(key, k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO,
						BigDecimal.ZERO });
				g[0] = g[0].add(debit);
				g[1] = g[1].add(credit);
				g[2] = running;
			}
		}
		for (var g : groups.entrySet()) {
			String key = g.getKey();
			LocalDate date = key.length() == 7 ? LocalDate.parse(key + "-01") : LocalDate.parse(key);
			lines.add(new LedgerLine(date, 0, null, "MONTHLY".equals(view) ? "MONTH" : "DAY", key,
					MoneyInput.text(g.getValue()[0]), MoneyInput.text(g.getValue()[1]), MoneyInput.text(g.getValue()[2]),
					false));
		}
		return new AccountLedger(accountId, account.name(), from, to, MoneyInput.text(opening), MoneyInput.text(in),
				MoneyInput.text(out), MoneyInput.text(running), lines);
	}

	private BigDecimal balanceBefore(long accountId, LocalDate date) {
		return MoneyInput.nz(jdbc.queryForObject("""
				select sum(l.debit - l.credit) from journal_line l join journal_entry j on j.id = l.entry_id
				where l.ledger_account_id = ? and j.entry_date < ?""", BigDecimal.class, accountId, Date.valueOf(date)));
	}

	// ------------------------------------------------- daily summary and close

	/** A day for one account (FR 11.9): opening, money in and out by source, closing, and the close if done. */
	@Transactional(readOnly = true)
	public DaySummary daySummary(long accountId, LocalDate date) {
		var account = setup.requireMoneyAccount(accountId);
		BigDecimal opening = balanceBefore(accountId, date);
		List<SummaryRow> bySource = jdbc.query("""
				select case j.entry_type when 'FEE_RECEIPT' then 'Fee receipts' when 'INCOME' then 'Other income'
				              when 'EXPENSE' then 'Expenses' when 'TRANSFER' then 'Transfers' when 'VENDOR' then 'Suppliers'
				              when 'SALARY' then 'Salaries' when 'REVERSAL' then 'Reversals' when 'OPENING' then 'Opening balance'
				              else j.entry_type end,
				       sum(l.debit), sum(l.credit)
				from journal_line l join journal_entry j on j.id = l.entry_id
				where l.ledger_account_id = ? and j.entry_date = ?
				group by 1 order by 1""", (rs, i) -> new SummaryRow(rs.getString(1),
				MoneyInput.text(rs.getBigDecimal(2)), MoneyInput.text(rs.getBigDecimal(3))), accountId, Date.valueOf(date));
		BigDecimal in = bySource.stream().map(r -> new BigDecimal(r.amountIn())).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal out = bySource.stream().map(r -> new BigDecimal(r.amountOut())).reduce(BigDecimal.ZERO, BigDecimal::add);
		List<DayCloseView> close = jdbc.query("""
				select id, close_date, closing, counted, difference, note, closed_by, closed_at from day_close
				where ledger_account_id = ? and close_date = ?""", (rs, i) -> new DayCloseView(rs.getLong(1),
				rs.getDate(2).toLocalDate(), MoneyInput.text(rs.getBigDecimal(3)), MoneyInput.text(rs.getBigDecimal(4)),
				MoneyInput.text(rs.getBigDecimal(5)), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()),
				accountId, Date.valueOf(date));
		return new DaySummary(accountId, account.name(), date, MoneyInput.text(opening), MoneyInput.text(in),
				MoneyInput.text(out), MoneyInput.text(opening.add(in).subtract(out)), bySource,
				close.isEmpty() ? null : close.getFirst());
	}

	/**
	 * Closes a day for a cash or bank account: the counted amount is recorded against the books; a difference needs a
	 * note (QA-ACC-004). Afterwards nothing can be posted to that account on or before that date.
	 */
	@Transactional
	public DaySummary closeDay(long accountId, LocalDate date, String countedRaw, String note) {
		setup.requireActiveMoneyAccount(accountId);
		LocalDate today = MoneyInput.today(clock);
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "A future day cannot be closed");
		}
		journal.lockCashAndBank(List.of(accountId));
		Long closed = jdbc.queryForObject(
				"select count(*) from day_close where ledger_account_id = ? and close_date >= ?", Long.class, accountId,
				Date.valueOf(date));
		if (closed != null && closed > 0) {
			throw ApiException.conflict("DAY_CLOSED", "This day (or a later one) is already closed");
		}
		DaySummary day = daySummary(accountId, date);
		BigDecimal counted = MoneyInput.parse(countedRaw, "Counted amount", true);
		BigDecimal closing = new BigDecimal(day.closing());
		BigDecimal difference = counted.subtract(closing);
		String why = PakFormats.trimToNull(note);
		if (difference.signum() != 0 && why == null) {
			throw ApiException.businessRule("NOTE_REQUIRED",
					"The count differs from the books by " + MoneyInput.rupees(difference.abs()) + ". Explain the difference.");
		}
		Long id = jdbc.queryForObject("""
				insert into day_close (school_id, ledger_account_id, close_date, opening, money_in, money_out, closing,
				                       counted, difference, note, closed_by)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
				accountId, Date.valueOf(date), new BigDecimal(day.opening()), new BigDecimal(day.totalIn()),
				new BigDecimal(day.totalOut()), closing, counted, difference, why, CurrentActor.require().displayName());
		audit.record("DAY_CLOSED", "day_close", id, null, Map.of("account", day.accountName(), "date", date.toString(),
				"closing", closing.toPlainString(), "counted", counted.toPlainString()), why);
		return daySummary(accountId, date);
	}

	@Transactional(readOnly = true)
	public List<DayCloseView> dayCloses(long accountId) {
		return jdbc.query("""
				select id, close_date, closing, counted, difference, note, closed_by, closed_at from day_close
				where ledger_account_id = ? order by close_date desc limit 100""", (rs, i) -> new DayCloseView(
				rs.getLong(1), rs.getDate(2).toLocalDate(), MoneyInput.text(rs.getBigDecimal(3)),
				MoneyInput.text(rs.getBigDecimal(4)), MoneyInput.text(rs.getBigDecimal(5)), rs.getString(6),
				rs.getString(7), rs.getTimestamp(8).toInstant()), accountId);
	}

	// --------------------------------------------------------- reconciliation

	/** Bank lines not yet seen on the bank statement (FR 11.10). */
	@Transactional(readOnly = true)
	public List<LedgerLine> uncleared(long accountId) {
		var account = setup.requireMoneyAccount(accountId);
		if (!"BANK".equals(account.subtype())) {
			throw ApiException.businessRule("NOT_BANK", "Reconciliation is for bank accounts");
		}
		return jdbc.query("""
				select j.entry_date, j.id, l.id, j.entry_type, j.memo, l.debit, l.credit
				from journal_line l join journal_entry j on j.id = l.entry_id
				where l.ledger_account_id = ? and not exists (select 1 from bank_clearance c where c.journal_line_id = l.id)
				order by j.entry_date, l.id""",
				(rs, i) -> new LedgerLine(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getString(4),
						rs.getString(5), rs.getBigDecimal(6).signum() > 0 ? MoneyInput.text(rs.getBigDecimal(6)) : null,
						rs.getBigDecimal(7).signum() > 0 ? MoneyInput.text(rs.getBigDecimal(7)) : null, null, false),
				accountId);
	}

	@Transactional
	public int markCleared(long accountId, List<Long> lineIds, LocalDate clearedOn, boolean cleared) {
		var account = setup.requireMoneyAccount(accountId);
		if (!"BANK".equals(account.subtype())) {
			throw ApiException.businessRule("NOT_BANK", "Reconciliation is for bank accounts");
		}
		LocalDate on = clearedOn == null ? MoneyInput.today(clock) : clearedOn;
		int changed = 0;
		for (long lineId : lineIds) {
			Long belongs = jdbc.queryForObject("select count(*) from journal_line where id = ? and ledger_account_id = ?",
					Long.class, lineId, accountId);
			if (belongs == null || belongs == 0) {
				throw ApiException.notFound("Bank entry");
			}
			changed += cleared ? jdbc.update("""
					insert into bank_clearance (journal_line_id, school_id, cleared_on, cleared_by) values (?, ?, ?, ?)
					on conflict do nothing""", lineId, TenantContext.requireSchoolId(), Date.valueOf(on),
					CurrentActor.require().displayName())
					: jdbc.update("delete from bank_clearance where journal_line_id = ?", lineId);
		}
		audit.record(cleared ? "BANK_CLEARED" : "BANK_UNCLEARED", "ledger_account", accountId, null,
				Map.of("lines", lineIds, "on", on.toString()));
		return changed;
	}

	// ---------------------------------------------------------------- reports

	/** Debit and credit totals per ledger account up to a date; Σ debit always equals Σ credit (QA-ACC-007). */
	@Transactional(readOnly = true)
	public List<BalanceRow> trialBalance(LocalDate asOf) {
		return jdbc.query("""
				select a.id, a.code, a.name, a.account_type, a.subtype, coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
				from ledger_account a
				left join (journal_line l join journal_entry j on j.id = l.entry_id and j.entry_date <= ?)
				       on l.ledger_account_id = a.id
				group by a.id, a.code, a.name, a.account_type, a.subtype
				having coalesce(sum(l.debit), 0) <> 0 or coalesce(sum(l.credit), 0) <> 0 or a.subtype in ('CASH', 'BANK')
				order by a.code""", (rs, i) -> {
			BigDecimal d = rs.getBigDecimal(6);
			BigDecimal c = rs.getBigDecimal(7);
			String type = rs.getString(4);
			// Natural balance: assets and expenses are debit balances, the rest credit balances.
			BigDecimal balance = "ASSET".equals(type) || "EXPENSE".equals(type) ? d.subtract(c) : c.subtract(d);
			return new BalanceRow(rs.getLong(1), rs.getString(2), rs.getString(3), type, rs.getString(5),
					MoneyInput.text(d), MoneyInput.text(c), MoneyInput.text(balance));
		}, Date.valueOf(asOf));
	}

	/** Assets = liabilities + equity + (income − expenses) (FR 11.13 balance sheet). */
	@Transactional(readOnly = true)
	public BalanceSheet balanceSheet(LocalDate asOf) {
		List<BalanceRow> all = trialBalance(asOf);
		List<BalanceRow> assets = all.stream().filter(r -> "ASSET".equals(r.type())).toList();
		List<BalanceRow> liabilities = all.stream().filter(r -> "LIABILITY".equals(r.type())).toList();
		List<BalanceRow> equity = all.stream().filter(r -> "EQUITY".equals(r.type())).toList();
		BigDecimal income = sum(all.stream().filter(r -> "INCOME".equals(r.type())).toList());
		BigDecimal expense = sum(all.stream().filter(r -> "EXPENSE".equals(r.type())).toList());
		return new BalanceSheet(asOf, assets, liabilities, equity, MoneyInput.text(sum(assets)),
				MoneyInput.text(sum(liabilities)), MoneyInput.text(sum(equity)), MoneyInput.text(income.subtract(expense)));
	}

	private static BigDecimal sum(List<BalanceRow> rows) {
		return rows.stream().map(r -> new BigDecimal(r.balance())).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** Income and expense per month (FR 11.13 income-expense summary). */
	@Transactional(readOnly = true)
	public List<MonthRow> monthlySummary(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select to_char(j.entry_date, 'YYYY-MM'),
				       coalesce(sum(l.credit - l.debit) filter (where a.account_type = 'INCOME'), 0),
				       coalesce(sum(l.debit - l.credit) filter (where a.account_type = 'EXPENSE'), 0)
				from journal_line l join journal_entry j on j.id = l.entry_id
				join ledger_account a on a.id = l.ledger_account_id
				where j.entry_date between ? and ? and a.account_type in ('INCOME', 'EXPENSE')
				group by 1 order by 1""", (rs, i) -> new MonthRow(rs.getString(1), MoneyInput.text(rs.getBigDecimal(2)),
				MoneyInput.text(rs.getBigDecimal(3)), MoneyInput.text(rs.getBigDecimal(2).subtract(rs.getBigDecimal(3)))),
				Date.valueOf(from), Date.valueOf(to));
	}

	/** Income and expense per category for a period (FR 11.13 monthly income/expense; matches the journal, QA-ACC-008). */
	@Transactional(readOnly = true)
	public List<CategoryRow> byCategory(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select a.code, a.name, a.account_type,
				       sum(case when a.account_type = 'INCOME' then l.credit - l.debit else l.debit - l.credit end)
				from journal_line l join journal_entry j on j.id = l.entry_id
				join ledger_account a on a.id = l.ledger_account_id
				where j.entry_date between ? and ? and a.account_type in ('INCOME', 'EXPENSE')
				group by a.code, a.name, a.account_type order by a.account_type desc, a.code""",
				(rs, i) -> new CategoryRow(rs.getString(1), rs.getString(2), rs.getString(3),
						MoneyInput.text(rs.getBigDecimal(4))), Date.valueOf(from), Date.valueOf(to));
	}

	/** Money received per day per cash/bank account (FR 11.13 daily collection). */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> dailyCollection(LocalDate from, LocalDate to) {
		List<Map<String, Object>> rows = new ArrayList<>();
		jdbc.query("""
				select j.entry_date, a.name, sum(l.debit) filter (where j.entry_type = 'FEE_RECEIPT'),
				       sum(l.debit) filter (where j.entry_type = 'INCOME'), sum(l.debit)
				from journal_line l join journal_entry j on j.id = l.entry_id
				join ledger_account a on a.id = l.ledger_account_id
				where j.entry_date between ? and ? and a.subtype in ('CASH', 'BANK') and l.debit > 0
				  and j.entry_type in ('FEE_RECEIPT', 'INCOME')
				group by j.entry_date, a.name order by j.entry_date, a.name""", rs -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", rs.getDate(1).toLocalDate().toString());
			row.put("account", rs.getString(2));
			row.put("fees", MoneyInput.text(MoneyInput.nz(rs.getBigDecimal(3))));
			row.put("otherIncome", MoneyInput.text(MoneyInput.nz(rs.getBigDecimal(4))));
			row.put("total", MoneyInput.text(rs.getBigDecimal(5)));
			rows.add(row);
		}, Date.valueOf(from), Date.valueOf(to));
		return rows;
	}

	/** Financial activity history (FR 11.12): who did what to money records, newest first. */
	@Transactional(readOnly = true)
	public PageResponse<ActivityRow> activity(LocalDate from, LocalDate to, int page, int size) {
		String where = " " + """
				where (a.entity_type in ('payment', 'bill_line', 'fee_run', 'money_entry', 'ledger_account', 'day_close',
				        'account_category', 'vendor', 'salary_sheet', 'discount')
				     or a.action like 'FEE%' or a.action like 'PAYMENT%' or a.action like 'SALARY%')
				   and a.at >= ? and a.at < ?""";
		Object[] args = { java.sql.Timestamp.valueOf(from.atStartOfDay()),
				java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay()) };
		Long total = jdbc.queryForObject("select count(*) from audit_log a" + where, Long.class, args);
		List<ActivityRow> rows = jdbc.query("""
				select a.id, a.at, a.action, a.entity_type, a.entity_id, a.reason,
				       coalesce(u.display_name, a.actor_type) from audit_log a
				left join app_user u on u.id = a.actor_id and a.actor_type = 'USER'""" + where
				+ " order by a.at desc limit ? offset ?", (rs, i) -> new ActivityRow(rs.getLong(1),
						rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), (Long) rs.getObject(5),
						rs.getString(6), rs.getString(7)),
				args[0], args[1], size, (long) page * size);
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}
}
