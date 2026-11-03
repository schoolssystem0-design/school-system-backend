package com.selectcode.sms.accounts.app;

import com.selectcode.sms.shared.accounting.JournalWriter;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.events.SchoolCreatedEvent;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cash and bank accounts (FR 11.1), income and expense categories (FR 11.6) and suppliers (FR 11.7). */
@Service
public class AccountsSetupService {

	public record MoneyAccountView(long id, String code, String name, String subtype, String bankDetails, String status,
			String balance) {
	}

	public record CategoryView(long id, String name, String kind, long ledgerAccountId, String code, String systemCode,
			String status) {
	}

	public record VendorView(long id, String name, String phone, String address, String notes, String status,
			String balance) {
	}

	private final JdbcTemplate jdbc;

	private final JournalWriter journal;

	private final AuditService audit;

	private final Clock clock;

	AccountsSetupService(JdbcTemplate jdbc, JournalWriter journal, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.journal = journal;
		this.audit = audit;
		this.clock = clock;
	}

	/** New schools get the standard accounts and categories (after the fee accounts, same transaction). */
	@EventListener
	@Order(10)
	public void onSchoolCreated(SchoolCreatedEvent event) {
		seed(jdbc, event.schoolId());
	}

	/** Also used by tests that create schools directly. */
	public static void seed(JdbcTemplate jdbc, long schoolId) {
		Object[][] accounts = { { "3000", "Opening balances", "EQUITY", "OPENING", 10 },
				{ "2200", "Salaries payable", "LIABILITY", "SALARY_PAYABLE", 11 },
				{ "2300", "Tax deducted from salaries", "LIABILITY", "TAX_PAYABLE", 12 },
				{ "2400", "Owed to suppliers", "LIABILITY", "VENDOR_PAYABLE", 13 },
				{ "4900", "Deductions from salaries", "INCOME", "SALARY_DEDUCTION", 14 },
				{ "5000", "Salaries", "EXPENSE", "SALARY_EXPENSE", 15 },
				{ "4100", "Donations", "INCOME", "INCOME_HEAD", 20 }, { "4110", "Canteen", "INCOME", "INCOME_HEAD", 21 },
				{ "4190", "Other income", "INCOME", "INCOME_HEAD", 22 },
				{ "5100", "Utilities", "EXPENSE", "EXPENSE_HEAD", 30 },
				{ "5110", "Stationery and supplies", "EXPENSE", "EXPENSE_HEAD", 31 },
				{ "5120", "Repairs and maintenance", "EXPENSE", "EXPENSE_HEAD", 32 },
				{ "5130", "Rent", "EXPENSE", "EXPENSE_HEAD", 33 },
				{ "5190", "Other expenses", "EXPENSE", "EXPENSE_HEAD", 34 } };
		for (Object[] a : accounts) {
			Long id = jdbc.queryForObject("""
					insert into ledger_account (school_id, code, name, account_type, subtype, display_order)
					values (?, ?, ?, ?, ?, ?) returning id""", Long.class, schoolId, a[0], a[1], a[2], a[3], a[4]);
			String subtype = (String) a[3];
			if (List.of("INCOME_HEAD", "EXPENSE_HEAD", "SALARY_EXPENSE").contains(subtype)) {
				jdbc.update("""
						insert into account_category (school_id, name, kind, ledger_account_id, system_code)
						values (?, ?, ?, ?, ?)""", schoolId, a[1], "INCOME".equals(a[2]) ? "INCOME" : "EXPENSE", id,
						"SALARY_EXPENSE".equals(subtype) ? "SALARIES" : null);
			}
		}
	}

	// --------------------------------------------------------------- accounts

	/** Cash and bank accounts with their current balance. */
	@Transactional(readOnly = true)
	public List<MoneyAccountView> moneyAccounts(boolean includeInactive) {
		return jdbc.query("""
				select a.id, a.code, a.name, a.subtype, a.bank_details, a.status,
				       coalesce((select sum(l.debit - l.credit) from journal_line l where l.ledger_account_id = a.id), 0)
				from ledger_account a where a.subtype in ('CASH', 'BANK')""" + (includeInactive ? "" : " and a.status = 'ACTIVE'")
				+ " order by a.display_order, a.code",
				(rs, i) -> new MoneyAccountView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), MoneyInput.text(rs.getBigDecimal(7))));
	}

	public MoneyAccountView requireMoneyAccount(long id) {
		return moneyAccounts(true).stream().filter(a -> a.id() == id).findFirst()
			.orElseThrow(() -> ApiException.notFound("Account"));
	}

	public MoneyAccountView requireActiveMoneyAccount(long id) {
		MoneyAccountView a = requireMoneyAccount(id);
		if (!"ACTIVE".equals(a.status())) {
			throw ApiException.businessRule("ACCOUNT_INACTIVE", a.name() + " is closed");
		}
		return a;
	}

	/** A new cash box or bank account, optionally with its opening balance (Dr account / Cr opening balances). */
	@Transactional
	public MoneyAccountView createMoneyAccount(String name, String subtype, String bankDetails, String openingBalance,
			LocalDate openingDate) {
		String clean = name(name, 100);
		if (!"CASH".equals(subtype) && !"BANK".equals(subtype)) {
			throw ApiException.businessRule("TYPE_INVALID", "Choose cash or bank");
		}
		String code = nextCode("CASH".equals(subtype) ? 1000 : 1010, 1099);
		Long id;
		try {
			id = jdbc.queryForObject("""
					insert into ledger_account (school_id, code, name, account_type, subtype, display_order, bank_details)
					values (?, ?, ?, 'ASSET', ?, 2, ?) returning id""", Long.class, TenantContext.requireSchoolId(), code,
					clean, subtype, PakFormats.trimToNull(bankDetails));
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("CODE_TAKEN", "Could not number the account; try again");
		}
		audit.record("ACCOUNT_CREATED", "ledger_account", id, null, Map.of("name", clean, "type", subtype));
		BigDecimal opening = MoneyInput.parseOrZero(openingBalance, "Opening balance");
		if (opening.signum() > 0) {
			postOpening(id, opening, openingDate == null ? MoneyInput.today(clock) : openingDate);
		}
		return requireMoneyAccount(id);
	}

	/** Adds an opening balance to an existing account (e.g. money held before the system was used). */
	@Transactional
	public MoneyAccountView addOpeningBalance(long accountId, String amount, LocalDate date) {
		requireActiveMoneyAccount(accountId);
		postOpening(accountId, MoneyInput.parse(amount, "Opening balance", false), date == null ? MoneyInput.today(clock) : date);
		return requireMoneyAccount(accountId);
	}

	private void postOpening(long accountId, BigDecimal amount, LocalDate date) {
		long entry = journal.post(date, "OPENING", "LEDGER_ACCOUNT", accountId, "Opening balance", null,
				List.of(JournalWriter.Line.debit(accountId, amount),
						JournalWriter.Line.credit(journal.accountOf("OPENING"), amount)));
		audit.record("OPENING_BALANCE", "ledger_account", accountId, null,
				Map.of("amount", amount.toPlainString(), "date", date.toString(), "journalEntryId", entry));
	}

	@Transactional
	public MoneyAccountView updateMoneyAccount(long id, String name, String bankDetails, boolean active) {
		MoneyAccountView before = requireMoneyAccount(id);
		if (!active && new BigDecimal(before.balance()).signum() != 0) {
			throw ApiException.conflict("BALANCE_NOT_ZERO", "Move the money out before closing the account");
		}
		if (!active && moneyAccounts(false).stream().filter(a -> a.subtype().equals(before.subtype())).count() <= 1
				&& "ACTIVE".equals(before.status())) {
			throw ApiException.conflict("LAST_ACCOUNT", "Keep at least one active " + before.subtype().toLowerCase() + " account");
		}
		jdbc.update("update ledger_account set name = ?, bank_details = ?, status = ? where id = ?", name(name, 100),
				PakFormats.trimToNull(bankDetails), active ? "ACTIVE" : "INACTIVE", id);
		MoneyAccountView after = requireMoneyAccount(id);
		audit.record("ACCOUNT_UPDATED", "ledger_account", id, before, after);
		return after;
	}

	// ------------------------------------------------------------- categories

	@Transactional(readOnly = true)
	public List<CategoryView> categories(String kind) {
		return jdbc.query("""
				select c.id, c.name, c.kind, c.ledger_account_id, a.code, c.system_code, c.status
				from account_category c join ledger_account a on a.id = c.ledger_account_id
				where (?::text is null or c.kind = ?) order by c.kind, a.code""",
				(rs, i) -> new CategoryView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
						rs.getString(5), rs.getString(6), rs.getString(7)), kind, kind);
	}

	public CategoryView requireCategory(long id, String kind) {
		CategoryView c = categories(null).stream().filter(x -> x.id() == id).findFirst()
			.orElseThrow(() -> ApiException.notFound("Category"));
		if (!c.kind().equals(kind)) {
			throw ApiException.businessRule("CATEGORY_KIND", "Choose an " + kind.toLowerCase() + " category");
		}
		if (!"ACTIVE".equals(c.status())) {
			throw ApiException.businessRule("CATEGORY_INACTIVE", c.name() + " is switched off");
		}
		if ("SALARIES".equals(c.systemCode())) {
			throw ApiException.businessRule("USE_PAYROLL", "Salaries are paid through Payroll");
		}
		return c;
	}

	@Transactional
	public CategoryView createCategory(String name, String kind) {
		String clean = name(name, 80);
		if (!"INCOME".equals(kind) && !"EXPENSE".equals(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose income or expense");
		}
		String code = "INCOME".equals(kind) ? nextCode(4100, 4899) : nextCode(5100, 5999);
		long schoolId = TenantContext.requireSchoolId();
		Long accountId = jdbc.queryForObject("""
				insert into ledger_account (school_id, code, name, account_type, subtype, display_order)
				values (?, ?, ?, ?, ?, 40) returning id""", Long.class, schoolId, code, clean, kind,
				"INCOME".equals(kind) ? "INCOME_HEAD" : "EXPENSE_HEAD");
		Long id;
		try {
			id = jdbc.queryForObject("""
					insert into account_category (school_id, name, kind, ledger_account_id) values (?, ?, ?, ?) returning id""",
					Long.class, schoolId, clean, kind, accountId);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "A category called " + clean + " already exists");
		}
		audit.record("CATEGORY_CREATED", "account_category", id, null, Map.of("name", clean, "kind", kind));
		return categories(null).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
	}

	@Transactional
	public CategoryView updateCategory(long id, String name, boolean active) {
		CategoryView before = categories(null).stream().filter(c -> c.id() == id).findFirst()
			.orElseThrow(() -> ApiException.notFound("Category"));
		if (before.systemCode() != null && !active) {
			throw ApiException.conflict("SYSTEM_CATEGORY", "This category is used by payroll and cannot be switched off");
		}
		String clean = name(name, 80);
		try {
			jdbc.update("update account_category set name = ?, status = ? where id = ?", clean,
					active ? "ACTIVE" : "INACTIVE", id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "A category called " + clean + " already exists");
		}
		jdbc.update("update ledger_account set name = ? where id = ?", clean, before.ledgerAccountId());
		CategoryView after = categories(null).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
		audit.record("CATEGORY_UPDATED", "account_category", id, before, after);
		return after;
	}

	// ---------------------------------------------------------------- vendors

	/** Suppliers with what the school owes each (approved purchases − approved payments, reversals netted). */
	@Transactional(readOnly = true)
	public List<VendorView> vendors() {
		return jdbc.query("""
				select v.id, v.name, v.phone, v.address, v.notes, v.status,
				       coalesce((select sum(case when e.kind = 'PURCHASE' then e.amount else -e.amount end
				                             * case when e.reverses_entry_id is null then 1 else -1 end)
				                 from money_entry e join money_entry_decision d on d.entry_id = e.id and d.decision = 'APPROVED'
				                 where e.vendor_id = v.id and e.kind in ('PURCHASE', 'VENDOR_PAYMENT')), 0)
				from vendor v order by v.name""",
				(rs, i) -> new VendorView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), MoneyInput.text(rs.getBigDecimal(7))));
	}

	public VendorView requireVendor(long id) {
		return vendors().stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
	}

	@Transactional
	public VendorView saveVendor(Long id, String name, String phone, String address, String notes, boolean active) {
		String clean = name(name, 200);
		String cleanPhone = phone == null || phone.isBlank() ? null : PakFormats.phoneOrNull(phone);
		if (phone != null && !phone.isBlank() && cleanPhone == null) {
			throw ApiException.businessRule("PHONE_INVALID", "Enter a Pakistani phone number, e.g. 0300 1234567");
		}
		try {
			if (id == null) {
				Long newId = jdbc.queryForObject("""
						insert into vendor (school_id, name, phone, address, notes) values (?, ?, ?, ?, ?) returning id""",
						Long.class, TenantContext.requireSchoolId(), clean, cleanPhone, PakFormats.trimToNull(address),
						PakFormats.trimToNull(notes));
				audit.record("VENDOR_CREATED", "vendor", newId, null, Map.of("name", clean));
				return requireVendor(newId);
			}
			VendorView before = requireVendor(id);
			jdbc.update("update vendor set name = ?, phone = ?, address = ?, notes = ?, status = ? where id = ?", clean,
					cleanPhone, PakFormats.trimToNull(address), PakFormats.trimToNull(notes),
					active ? "ACTIVE" : "INACTIVE", id);
			VendorView after = requireVendor(id);
			audit.record("VENDOR_UPDATED", "vendor", id, before, after);
			return after;
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "A supplier called " + clean + " already exists");
		}
	}

	// ---------------------------------------------------------------- helpers

	private String nextCode(int from, int to) {
		List<String> used = jdbc.queryForList("select code from ledger_account", String.class);
		for (int c = from; c <= to; c++) {
			if (!used.contains(String.valueOf(c))) {
				return String.valueOf(c);
			}
		}
		throw ApiException.conflict("NO_CODES", "No more account numbers in this range");
	}

	private static String name(String raw, int max) {
		String clean = PakFormats.trimToNull(raw);
		if (clean == null || clean.length() > max) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter a name (up to " + max + " characters)");
		}
		return clean;
	}
}
