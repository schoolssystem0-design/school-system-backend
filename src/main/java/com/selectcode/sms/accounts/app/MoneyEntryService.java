package com.selectcode.sms.accounts.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.accounting.JournalWriter;
import com.selectcode.sms.shared.accounting.JournalWriter.Line;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.files.FileService;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money that is not fees (FR 11.2, 11.4, 11.5, 11.7): income, expenses, transfers between accounts, supplier bills
 * and supplier payments. An entry reaches the books only when approved; expenses and supplier payments above the
 * school's limit wait for someone with the approval permission (QA-ACC-003). Entries are never edited: a mistake is
 * reversed with a reason (QA-ACC-006).
 */
@Service
public class MoneyEntryService {

	public static final Set<String> KINDS = Set.of("INCOME", "EXPENSE", "TRANSFER", "PURCHASE", "VENDOR_PAYMENT");

	private static final Set<String> NEEDS_LIMIT = Set.of("EXPENSE", "PURCHASE", "VENDOR_PAYMENT");

	private static final Map<String, String> PREFIX = Map.of("INCOME", "INC", "EXPENSE", "EXP", "TRANSFER", "TRF",
			"PURCHASE", "PUR", "VENDOR_PAYMENT", "SUP");

	public record EntryInput(String kind, LocalDate entryDate, Long categoryId, Long accountId, Long toAccountId,
			Long vendorId, String amount, String reference, String description, Long attachmentFileId) {
	}

	public record EntryView(long id, String entryNo, String kind, LocalDate entryDate, Long categoryId,
			String categoryName, Long accountId, String accountName, Long toAccountId, String toAccountName,
			Long vendorId, String vendorName, String amount, String reference, String description,
			Long attachmentFileId, String status, String createdBy, Instant createdAt, String decidedBy,
			String decisionNote, Instant decidedAt, Long journalEntryId, Long reversesEntryId, String reversalReason,
			Long reversedByEntryId) {
	}

	private final JdbcTemplate jdbc;

	private final AccountsSetupService setup;

	private final JournalWriter journal;

	private final NumberSequences sequences;

	private final SettingsService settings;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final FileService files;

	private final AuditService audit;

	private final Clock clock;

	MoneyEntryService(JdbcTemplate jdbc, AccountsSetupService setup, JournalWriter journal, NumberSequences sequences,
			SettingsService settings, YearContext yearContext, AcademicYearLookup years, FileService files,
			AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.setup = setup;
		this.journal = journal;
		this.sequences = sequences;
		this.settings = settings;
		this.yearContext = yearContext;
		this.years = years;
		this.files = files;
		this.audit = audit;
		this.clock = clock;
	}

	/** Requires an Idempotency-Key: the same key returns the entry made the first time (double click, retry). */
	@Transactional
	public EntryView create(EntryInput in, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		String key = idempotencyKey.trim();
		jdbc.queryForList("select pg_advisory_xact_lock(7304, hashtext(?))", Object.class,
				TenantContext.requireSchoolId() + ":" + key);
		List<Object[]> earlier = jdbc.query("select id, kind, amount from money_entry where idempotency_key = ?",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getBigDecimal(3) }, key);
		if (!earlier.isEmpty()) {
			Object[] e = earlier.getFirst();
			if (!e[1].equals(in.kind()) || ((BigDecimal) e[2]).compareTo(MoneyInput.parse(in.amount(), "Amount", false)) != 0) {
				throw ApiException.businessRule("IDEMPOTENCY_KEY_REUSED", "This request key was already used for another entry");
			}
			return get((Long) e[0]);
		}
		String kind = in.kind();
		if (kind == null || !KINDS.contains(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose income, expense, transfer or a supplier entry");
		}
		YearRef year = yearContext.requireWritable();
		LocalDate today = MoneyInput.today(clock);
		LocalDate date = in.entryDate() == null ? today : in.entryDate();
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "The date cannot be in the future");
		}
		if (date.isBefore(year.startDate()) || date.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The date must be within " + year.name());
		}
		BigDecimal amount = MoneyInput.parse(in.amount(), "Amount", false);
		Long categoryId = null;
		Long accountId = null;
		Long toAccountId = null;
		Long vendorId = null;
		switch (kind) {
			case "INCOME", "EXPENSE" -> {
				categoryId = setup.requireCategory(require(in.categoryId(), "category"), kind).id();
				accountId = setup.requireActiveMoneyAccount(require(in.accountId(), "account")).id();
			}
			case "TRANSFER" -> {
				accountId = setup.requireActiveMoneyAccount(require(in.accountId(), "account to move from")).id();
				toAccountId = setup.requireActiveMoneyAccount(require(in.toAccountId(), "account to move to")).id();
				if (accountId.equals(toAccountId)) {
					throw ApiException.businessRule("SAME_ACCOUNT", "Choose two different accounts");
				}
			}
			case "PURCHASE" -> {
				categoryId = setup.requireCategory(require(in.categoryId(), "category"), "EXPENSE").id();
				vendorId = activeVendor(require(in.vendorId(), "supplier"));
			}
			default -> {
				vendorId = activeVendor(require(in.vendorId(), "supplier"));
				accountId = setup.requireActiveMoneyAccount(require(in.accountId(), "account")).id();
			}
		}
		if (in.attachmentFileId() != null) {
			var file = files.find(in.attachmentFileId())
				.orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "Attachment not found"));
			if (!"ACCOUNT_DOCUMENT".equals(file.purpose())) {
				throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "Upload the bill or receipt as an accounts document");
			}
		}
		boolean canApprove = CurrentActor.hasPermission(Permissions.ACCOUNTS_ENTRY_APPROVE);
		boolean overLimit = NEEDS_LIMIT.contains(kind)
				&& amount.compareTo(settings.money(SettingKey.EXPENSE_APPROVAL_LIMIT).amount()) > 0;
		boolean needsApproval = overLimit && !canApprove;
		String entryNo = NumberSequences.format(PREFIX.get(kind), sequences.next("money_entry." + kind), 5);
		Long id = jdbc.queryForObject("""
				insert into money_entry (school_id, entry_no, kind, entry_date, academic_year_id, category_id, account_id,
				                         to_account_id, vendor_id, amount, reference, description, attachment_file_id,
				                         needs_approval, created_by, idempotency_key)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireSchoolId(), entryNo, kind, Date.valueOf(date), year.id(), categoryId, accountId,
				toAccountId, vendorId, amount, PakFormats.trimToNull(in.reference()), PakFormats.trimToNull(in.description()),
				in.attachmentFileId(), needsApproval, CurrentActor.require().displayName(), key);
		audit.record("MONEY_ENTRY_CREATED", "money_entry", id, null,
				Map.of("entryNo", entryNo, "kind", kind, "amount", amount.toPlainString(), "needsApproval", needsApproval));
		if (!needsApproval) {
			approveInternal(id, overLimit ? "Approved when entered" : "Within the approval limit");
		}
		return get(id);
	}

	@Transactional
	public EntryView decide(long id, boolean approve, String note) {
		EntryView e = get(id);
		if (!"PENDING".equals(e.status())) {
			throw ApiException.conflict("ALREADY_DECIDED", "This entry is already " + e.status().toLowerCase());
		}
		String clean = PakFormats.trimToNull(note);
		if (approve) {
			approveInternal(id, clean);
		}
		else {
			if (clean == null) {
				throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for rejecting");
			}
			jdbc.update("""
					insert into money_entry_decision (entry_id, school_id, decision, decided_by, note)
					values (?, ?, 'REJECTED', ?, ?)""", id, TenantContext.requireSchoolId(),
					CurrentActor.require().displayName(), clean);
			audit.record("MONEY_ENTRY_REJECTED", "money_entry", id, null, Map.of("entryNo", e.entryNo()), clean);
		}
		return get(id);
	}

	private void approveInternal(long id, String note) {
		EntryView e = get(id);
		BigDecimal amount = new BigDecimal(e.amount());
		List<Line> lines = switch (e.kind()) {
			case "INCOME" -> List.of(Line.debit(e.accountId(), amount), Line.credit(categoryAccount(e.categoryId()), amount));
			case "EXPENSE" -> List.of(Line.debit(categoryAccount(e.categoryId()), amount), Line.credit(e.accountId(), amount));
			case "TRANSFER" -> List.of(Line.debit(e.toAccountId(), amount), Line.credit(e.accountId(), amount));
			case "PURCHASE" -> List.of(Line.debit(categoryAccount(e.categoryId()), amount),
					Line.credit(journal.accountOf("VENDOR_PAYABLE"), amount));
			default -> List.of(Line.debit(journal.accountOf("VENDOR_PAYABLE"), amount), Line.credit(e.accountId(), amount));
		};
		String type = switch (e.kind()) {
			case "INCOME" -> "INCOME";
			case "EXPENSE" -> "EXPENSE";
			case "TRANSFER" -> "TRANSFER";
			default -> "VENDOR";
		};
		long entry = journal.post(e.entryDate(), type, "MONEY_ENTRY", id, label(e), null, lines);
		jdbc.update("""
				insert into money_entry_decision (entry_id, school_id, decision, decided_by, note, journal_entry_id)
				values (?, ?, 'APPROVED', ?, ?, ?)""", id, TenantContext.requireSchoolId(),
				CurrentActor.require().displayName(), note, entry);
		audit.record("MONEY_ENTRY_APPROVED", "money_entry", id, null, Map.of("entryNo", e.entryNo(), "journalEntryId", entry),
				note);
	}

	/** Undoes an approved entry with a mirror entry dated today; both stay visible (FR 11.11). */
	@Transactional
	public EntryView reverse(long id, String reason) {
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the reversal");
		}
		EntryView e = get(id);
		if (e.reversesEntryId() != null) {
			throw ApiException.businessRule("IS_REVERSAL", "A reversal cannot be reversed");
		}
		if (!"APPROVED".equals(e.status())) {
			throw ApiException.businessRule("NOT_APPROVED", "Only approved entries can be reversed; reject a pending one");
		}
		if (e.reversedByEntryId() != null) {
			throw ApiException.conflict("ALREADY_REVERSED", "This entry is already reversed");
		}
		Long yearId = jdbc.queryForObject("select academic_year_id from money_entry where id = ?", Long.class, id);
		yearContext.requireWritable(years.findById(yearId).orElseThrow());
		LocalDate today = MoneyInput.today(clock);
		long schoolId = TenantContext.requireSchoolId();
		Long reversalId = jdbc.queryForObject("""
				insert into money_entry (school_id, entry_no, kind, entry_date, academic_year_id, category_id, account_id,
				                         to_account_id, vendor_id, amount, reference, description, reverses_entry_id,
				                         reversal_reason, created_by)
				select school_id, entry_no || '-R', kind, ?, academic_year_id, category_id, account_id, to_account_id,
				       vendor_id, amount, reference, description, id, ?, ?
				from money_entry where id = ? returning id""", Long.class, Date.valueOf(today), why,
				CurrentActor.require().displayName(), id);
		long entry = journal.reverse(e.journalEntryId(), today, "MONEY_ENTRY_REVERSAL", reversalId,
				"Reversal of " + e.entryNo() + ": " + why);
		jdbc.update("""
				insert into money_entry_decision (entry_id, school_id, decision, decided_by, note, journal_entry_id)
				values (?, ?, 'APPROVED', ?, ?, ?)""", reversalId, schoolId, CurrentActor.require().displayName(), why,
				entry);
		audit.record("MONEY_ENTRY_REVERSED", "money_entry", id, Map.of("entryNo", e.entryNo(), "amount", e.amount()),
				Map.of("reversalId", reversalId), why);
		return get(id);
	}

	@Transactional(readOnly = true)
	public EntryView get(long id) {
		List<EntryView> rows = query(" where e.id = ?", List.of(id), 1, 0);
		if (rows.isEmpty()) {
			throw ApiException.notFound("Entry");
		}
		return rows.getFirst();
	}

	@Transactional(readOnly = true)
	public PageResponse<EntryView> list(String kind, String status, LocalDate from, LocalDate to, Long accountId,
			Long categoryId, Long vendorId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> args = new ArrayList<>();
		if (kind != null && !kind.isBlank()) {
			where.append(" and e.kind = ?");
			args.add(kind);
		}
		if (status != null && !status.isBlank()) {
			where.append(" and coalesce(d.decision, 'PENDING') = ?");
			args.add(status);
		}
		if (from != null) {
			where.append(" and e.entry_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and e.entry_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (accountId != null) {
			where.append(" and (e.account_id = ? or e.to_account_id = ?)");
			args.add(accountId);
			args.add(accountId);
		}
		if (categoryId != null) {
			where.append(" and e.category_id = ?");
			args.add(categoryId);
		}
		if (vendorId != null) {
			where.append(" and e.vendor_id = ?");
			args.add(vendorId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (e.entry_no ilike ? or e.description ilike ? or e.reference ilike ?)");
			String like = "%" + q.trim() + "%";
			args.addAll(List.of(like, like, like));
		}
		Long total = jdbc.queryForObject("select count(*) from money_entry e"
				+ " left join money_entry_decision d on d.entry_id = e.id" + where, Long.class, args.toArray());
		return PageResponse.of(query(where.toString(), args, size, (long) page * size), page, size,
				total == null ? 0 : total);
	}

	private List<EntryView> query(String where, List<Object> args, int limit, long offset) {
		List<Object> all = new ArrayList<>(args);
		all.add(limit);
		all.add(offset);
		return jdbc.query("""
				select e.id, e.entry_no, e.kind, e.entry_date, e.category_id, c.name, e.account_id, a.name,
				       e.to_account_id, t.name, e.vendor_id, v.name, e.amount, e.reference, e.description,
				       e.attachment_file_id,
				       case when d.decision is null then 'PENDING' else d.decision end, e.created_by, e.created_at,
				       d.decided_by, d.note, d.decided_at, d.journal_entry_id, e.reverses_entry_id, e.reversal_reason,
				       (select r.id from money_entry r where r.reverses_entry_id = e.id)
				from money_entry e
				left join money_entry_decision d on d.entry_id = e.id
				left join account_category c on c.id = e.category_id
				left join ledger_account a on a.id = e.account_id
				left join ledger_account t on t.id = e.to_account_id
				left join vendor v on v.id = e.vendor_id""" + where + " order by e.entry_date desc, e.id desc limit ? offset ?",
				(rs, i) -> new EntryView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(),
						(Long) rs.getObject(5), rs.getString(6), (Long) rs.getObject(7), rs.getString(8),
						(Long) rs.getObject(9), rs.getString(10), (Long) rs.getObject(11), rs.getString(12),
						MoneyInput.text(rs.getBigDecimal(13)), rs.getString(14), rs.getString(15), (Long) rs.getObject(16),
						rs.getString(17), rs.getString(18), rs.getTimestamp(19).toInstant(), rs.getString(20),
						rs.getString(21), rs.getTimestamp(22) == null ? null : rs.getTimestamp(22).toInstant(),
						(Long) rs.getObject(23), (Long) rs.getObject(24), rs.getString(25), (Long) rs.getObject(26)),
				all.toArray());
	}

	private long categoryAccount(long categoryId) {
		return jdbc.queryForObject("select ledger_account_id from account_category where id = ?", Long.class, categoryId);
	}

	private long activeVendor(long id) {
		var v = setup.requireVendor(id);
		if (!"ACTIVE".equals(v.status())) {
			throw ApiException.businessRule("VENDOR_INACTIVE", v.name() + " is switched off");
		}
		return id;
	}

	private static long require(Long value, String what) {
		if (value == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose the " + what);
		}
		return value;
	}

	private static String label(EntryView e) {
		String what = switch (e.kind()) {
			case "INCOME" -> "Income";
			case "EXPENSE" -> "Expense";
			case "TRANSFER" -> "Transfer";
			case "PURCHASE" -> "Supplier bill";
			default -> "Supplier payment";
		};
		return what + " " + e.entryNo() + (e.description() == null ? "" : ": " + e.description());
	}
}
