package com.selectcode.sms.fees.app;

import com.selectcode.sms.fees.app.OpenLines.OpenLine;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fee payments (FR 10.7–10.10, 10.18). One call = one transaction: payment, receipt number, allocations, journal
 * entry, receipt SMS (queued) and audit row commit together or not at all (QA-FEE-011). Payments are never edited:
 * a mistake is corrected by a reversal with a reason.
 */
@Service
public class PaymentService {

	public static final Set<String> METHODS = Set.of("CASH", "BANK", "CHEQUE", "ONLINE");

	public record AllocationInput(long billLineId, String amount) {
	}

	public record PaymentInput(Long familyId, Long studentId, String amount, LocalDate paymentDate, String method,
			Long accountId, String reference, String remarks, List<AllocationInput> allocations) {
	}

	public record AllocationView(long billLineId, String billNo, long studentId, String studentName, String grNo,
			String headName, String periodKey, String description, String amount, String kind, LocalDate date) {
	}

	public record PaymentView(long id, String receiptNo, long familyId, String familyNo, String fatherName,
			Long studentId, String studentName, LocalDate paymentDate, String method, long accountId,
			String accountName, String amount, String reference, String remarks, String receivedBy,
			Instant createdAt, Long reversesPaymentId, String reversalReason, Long reversedByPaymentId,
			String creditRemaining, String familyBalance, Long journalEntryId, List<AllocationView> allocations,
			boolean replayed) {
	}

	public record PaymentRow(long id, String receiptNo, LocalDate paymentDate, String familyNo, String fatherName,
			String studentName, String method, String accountName, String amount, String receivedBy,
			Long reversesPaymentId, boolean reversed) {
	}

	private final JdbcTemplate jdbc;

	private final OpenLines openLines;

	private final CreditApplier credit;

	private final FeeLedger ledger;

	private final NumberSequences sequences;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final SettingsService settings;

	private final MessageOutbox outbox;

	private final MessageTemplates templates;

	private final AuditService audit;

	private final Clock clock;

	PaymentService(JdbcTemplate jdbc, OpenLines openLines, CreditApplier credit, FeeLedger ledger,
			NumberSequences sequences, YearContext yearContext, AcademicYearLookup years, SettingsService settings,
			MessageOutbox outbox, MessageTemplates templates, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.openLines = openLines;
		this.credit = credit;
		this.ledger = ledger;
		this.sequences = sequences;
		this.yearContext = yearContext;
		this.years = years;
		this.settings = settings;
		this.outbox = outbox;
		this.templates = templates;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public PaymentView record(PaymentInput in, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		String key = idempotencyKey.trim();
		String hash = hash(in);
		// Same key again (double click, network retry): return the first payment, never a second one (QA-FEE-012).
		ledger.lockKey("payment", key);
		List<Object[]> earlier = jdbc.query("select id, request_hash from payment where idempotency_key = ?",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }, key);
		if (!earlier.isEmpty()) {
			if (!hash.equals(earlier.getFirst()[1])) {
				throw ApiException.businessRule("IDEMPOTENCY_KEY_REUSED",
						"This request key was already used for a different payment");
			}
			return withReplay(get((Long) earlier.getFirst()[0]));
		}
		long familyId = family(in);
		ledger.lockFamily(familyId);
		YearRef year = yearContext.requireWritable();
		LocalDate today = Amounts.today(clock);
		LocalDate date = in.paymentDate() == null ? today : in.paymentDate();
		if (date.isAfter(today)) {
			throw ApiException.businessRule("DATE_IN_FUTURE", "The payment date cannot be in the future");
		}
		if (in.method() == null || !METHODS.contains(in.method())) {
			throw ApiException.businessRule("METHOD_INVALID", "Choose cash, bank, cheque or online");
		}
		if (in.accountId() == null) {
			throw ApiException.businessRule("ACCOUNT_INVALID", "Choose the cash or bank account");
		}
		FeeLedger.AccountView account = ledger.requireReceivingAccount(in.accountId());
		BigDecimal amount = Amounts.parse(in.amount(), "Amount", false);

		// Allocation: manual (cashier chose lines) or oldest due first. Anything left becomes advance credit.
		List<OpenLine> open = openLines.forFamily(familyId, in.studentId());
		Map<Long, OpenLine> byId = new HashMap<>();
		open.forEach(l -> byId.put(l.billLineId(), l));
		Map<Long, BigDecimal> allocations = new LinkedHashMap<>();
		if (in.allocations() != null && !in.allocations().isEmpty()) {
			BigDecimal sum = BigDecimal.ZERO;
			for (AllocationInput a : in.allocations()) {
				OpenLine line = byId.get(a.billLineId());
				BigDecimal part = Amounts.parse(a.amount(), "Allocated amount", true);
				if (part.signum() == 0) {
					continue;
				}
				if (line == null || part.compareTo(line.balanceAmount()) > 0) {
					throw ApiException.conflict("BALANCE_CHANGED",
							"A fee line is already paid or its balance changed. Reload and try again.");
				}
				allocations.merge(a.billLineId(), part, BigDecimal::add);
				sum = sum.add(part);
			}
			if (sum.compareTo(amount) > 0) {
				throw ApiException.businessRule("ALLOCATION_TOO_HIGH", "The allocated amounts add up to more than the payment");
			}
		}
		else {
			BigDecimal left = amount;
			for (OpenLine line : open) {
				if (left.signum() <= 0) {
					break;
				}
				BigDecimal part = line.balanceAmount().min(left);
				allocations.put(line.billLineId(), part);
				left = left.subtract(part);
			}
		}
		BigDecimal allocated = allocations.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal advance = amount.subtract(allocated);

		long schoolId = TenantContext.requireSchoolId();
		String receiptNo = year.name() + "/" + String.format("%05d", sequences.next("receipt." + year.id()));
		String actor = CurrentActor.require().displayName();
		Long paymentId = jdbc.queryForObject("""
				insert into payment (school_id, receipt_no, family_id, student_id, academic_year_id, payment_date, method,
				                     ledger_account_id, amount, reference, remarks, received_by, idempotency_key,
				                     request_hash)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, schoolId, receiptNo,
				familyId, in.studentId(), year.id(), Date.valueOf(date), in.method(), account.id(), amount,
				PakFormats.trimToNull(in.reference()), PakFormats.trimToNull(in.remarks()), actor, key, hash);
		List<FeeLedger.Line> lines = new ArrayList<>();
		lines.add(FeeLedger.Line.debit(account.id(), amount));
		lines.add(FeeLedger.Line.credit(ledger.accountOf(FeeLedger.FEE_INCOME), allocated));
		lines.add(FeeLedger.Line.credit(ledger.accountOf(FeeLedger.STUDENT_ADVANCE), advance));
		long entry = ledger.post(date, "FEE_RECEIPT", "PAYMENT", paymentId, "Fee receipt " + receiptNo, null, lines);
		for (Map.Entry<Long, BigDecimal> a : allocations.entrySet()) {
			jdbc.update("""
					insert into payment_allocation (school_id, payment_id, bill_line_id, amount, kind, allocation_date,
					                                journal_entry_id)
					values (?, ?, ?, ?, 'PAYMENT', ?, ?)""", schoolId, paymentId, a.getKey(), a.getValue(),
					Date.valueOf(date), entry);
		}
		audit.record("PAYMENT_RECEIVED", "payment", paymentId, null,
				Map.of("receiptNo", receiptNo, "familyId", familyId, "amount", amount.toPlainString(), "method",
						in.method(), "account", account.name(), "allocated", allocated.toPlainString(), "advance",
						advance.toPlainString()));
		queueReceiptSms(paymentId, familyId, receiptNo, amount, date);
		return get(paymentId);
	}

	private void queueReceiptSms(long paymentId, long familyId, String receiptNo, BigDecimal amount, LocalDate date) {
		if (!settings.bool(SettingKey.SEND_RECEIPT_SMS)) {
			return;
		}
		var family = jdbc.queryForMap(
				"select father_phone, mother_phone, preferred_channel, family_no from family where id = ?", familyId);
		if ("NONE".equals(family.get("preferred_channel"))) {
			return;
		}
		String phone = family.get("father_phone") != null ? (String) family.get("father_phone")
				: (String) family.get("mother_phone");
		if (phone == null) {
			return;
		}
		BigDecimal due = openLines.owed(familyId).subtract(openLines.creditTotal(familyId));
		String body = templates.render(MessageTemplates.Code.FEE_RECEIPT, Map.of("amount", Amounts.rupees(amount), "date",
				com.selectcode.sms.shared.pdf.PdfKit.date(date), "family", String.valueOf(family.get("family_no")), "receipt",
				receiptNo, "balance", due.signum() > 0 ? "Balance due " + Amounts.rupees(due) + "."
						: due.signum() < 0 ? "Advance credit " + Amounts.rupees(due.negate()) + "." : "No dues."));
		outbox.queueSms(phone, body, "FEE_RECEIPT", "payment", paymentId, null);
	}

	/** Reverses a payment completely (wrong amount, bounced cheque): a new negative entry; the original stays. */
	@Transactional
	public PaymentView reverse(long paymentId, String reason) {
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the reversal");
		}
		Map<String, Object> p = jdbc.queryForList("""
				select family_id, student_id, academic_year_id, method, ledger_account_id, amount, receipt_no,
				       reverses_payment_id, payment_date
				from payment where id = ?""", paymentId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Payment"));
		if (p.get("reverses_payment_id") != null) {
			throw ApiException.businessRule("IS_REVERSAL", "A reversal cannot be reversed");
		}
		long familyId = ((Number) p.get("family_id")).longValue();
		ledger.lockFamily(familyId);
		Long done = jdbc.queryForObject("select count(*) from payment where reverses_payment_id = ?", Long.class,
				paymentId);
		if (done != null && done > 0) {
			throw ApiException.conflict("ALREADY_REVERSED", "This payment is already reversed");
		}
		YearRef year = years.findById(((Number) p.get("academic_year_id")).longValue())
			.orElseThrow(() -> ApiException.notFound("Academic year"));
		yearContext.requireWritable(year);
		BigDecimal amount = (BigDecimal) p.get("amount");
		LocalDate today = Amounts.today(clock);
		long schoolId = TenantContext.requireSchoolId();
		Long reversalId = jdbc.queryForObject("""
				insert into payment (school_id, family_id, student_id, academic_year_id, payment_date, method,
				                     ledger_account_id, amount, received_by, reverses_payment_id, reversal_reason)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, schoolId, familyId,
				p.get("student_id"), year.id(), Date.valueOf(today), p.get("method"), p.get("ledger_account_id"),
				amount.negate(), CurrentActor.require().displayName(), paymentId, why);
		List<Object[]> allocs = jdbc.query("""
				select bill_line_id, sum(amount) from payment_allocation where payment_id = ?
				group by bill_line_id having sum(amount) <> 0""",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getBigDecimal(2) }, paymentId);
		BigDecimal allocated = allocs.stream().map(a -> (BigDecimal) a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal advance = amount.subtract(allocated);
		List<Long> original = jdbc.queryForList(
				"select id from journal_entry where source_type = 'PAYMENT' and source_id = ? order by id limit 1",
				Long.class, paymentId);
		long account = ((Number) p.get("ledger_account_id")).longValue();
		long entry = ledger.post(today, "REVERSAL", "PAYMENT_REVERSAL", reversalId,
				"Reversal of receipt " + p.get("receipt_no") + ": " + why, original.isEmpty() ? null : original.getFirst(),
				List.of(FeeLedger.Line.credit(account, amount),
						FeeLedger.Line.debit(ledger.accountOf(FeeLedger.FEE_INCOME), allocated),
						FeeLedger.Line.debit(ledger.accountOf(FeeLedger.STUDENT_ADVANCE), advance)));
		for (Object[] a : allocs) {
			jdbc.update("""
					insert into payment_allocation (school_id, payment_id, bill_line_id, amount, kind, allocation_date,
					                                journal_entry_id)
					values (?, ?, ?, ?, 'REVERSAL', ?, ?)""", schoolId, reversalId, a[0], ((BigDecimal) a[1]).negate(),
					Date.valueOf(today), entry);
		}
		audit.record("PAYMENT_REVERSED", "payment", paymentId, Map.of("receiptNo", p.get("receipt_no"), "amount",
				amount.toPlainString()), Map.of("reversalId", reversalId), why);
		// Credit freed by the reversal of another payment is not created; any other credit the family has is reused.
		credit.apply(familyId, today);
		return get(paymentId);
	}

	/** Applies the family's advance credit to its open lines now. */
	@Transactional
	public String applyCredit(long familyId) {
		yearContext.requireWritable();
		return Amounts.text(credit.apply(familyId, Amounts.today(clock)));
	}

	/** Everything about one payment, for the receipt and for an auditor (FR 10.18). */
	@Transactional(readOnly = true)
	public PaymentView get(long id) {
		List<PaymentView> rows = jdbc.query("""
				select p.id, p.receipt_no, p.family_id, f.family_no, f.father_name, p.student_id, s.name, p.payment_date,
				       p.method, p.ledger_account_id, a.name, p.amount, p.reference, p.remarks, p.received_by,
				       p.created_at, p.reverses_payment_id, p.reversal_reason,
				       (select r.id from payment r where r.reverses_payment_id = p.id),
				       (select c.remaining from payment_credit c where c.payment_id = p.id),
				       (select j.id from journal_entry j where j.source_type in ('PAYMENT', 'PAYMENT_REVERSAL')
				          and j.source_id = p.id order by j.id limit 1)
				from payment p join family f on f.id = p.family_id join ledger_account a on a.id = p.ledger_account_id
				left join student s on s.id = p.student_id
				where p.id = ?""", (rs, i) -> {
			long familyId = rs.getLong(3);
			BigDecimal balance = openLines.owed(familyId).subtract(openLines.creditTotal(familyId));
			return new PaymentView(rs.getLong(1), rs.getString(2), familyId, rs.getString(4), rs.getString(5),
					(Long) rs.getObject(6), rs.getString(7), rs.getDate(8).toLocalDate(), rs.getString(9),
					rs.getLong(10), rs.getString(11), Amounts.text(rs.getBigDecimal(12)), rs.getString(13),
					rs.getString(14), rs.getString(15), rs.getTimestamp(16).toInstant(), (Long) rs.getObject(17),
					rs.getString(18), (Long) rs.getObject(19), Amounts.text(Amounts.nz(rs.getBigDecimal(20))),
					Amounts.text(balance), (Long) rs.getObject(21), allocations(rs.getLong(1)), false);
		}, id);
		if (rows.isEmpty()) {
			throw ApiException.notFound("Payment");
		}
		return rows.getFirst();
	}

	private List<AllocationView> allocations(long paymentId) {
		return jdbc.query("""
				select pa.bill_line_id, b.bill_no, bl.student_id, s.name, s.gr_no, h.name, bl.period_key, bl.description,
				       pa.amount, pa.kind, pa.allocation_date
				from payment_allocation pa join bill_line bl on bl.id = pa.bill_line_id join bill b on b.id = bl.bill_id
				join student s on s.id = bl.student_id join fee_head h on h.id = bl.fee_head_id
				where pa.payment_id = ? order by pa.id""", (rs, i) -> new AllocationView(rs.getLong(1),
				rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
				rs.getString(8), Amounts.text(rs.getBigDecimal(9)), rs.getString(10), rs.getDate(11).toLocalDate()),
				paymentId);
	}

	/** Recent payments (FR 10.17), newest first, with filters. */
	@Transactional(readOnly = true)
	public PageResponse<PaymentRow> list(LocalDate from, LocalDate to, String method, Long accountId, Long familyId,
			String q, int page, int size) {
		List<Object> args = new ArrayList<>();
		StringBuilder where = new StringBuilder(" where 1 = 1");
		if (from != null) {
			where.append(" and p.payment_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and p.payment_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (method != null && !method.isBlank()) {
			where.append(" and p.method = ?");
			args.add(method);
		}
		if (accountId != null) {
			where.append(" and p.ledger_account_id = ?");
			args.add(accountId);
		}
		if (familyId != null) {
			where.append(" and p.family_id = ?");
			args.add(familyId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (p.receipt_no ilike ? or f.family_no ilike ? or f.father_name ilike ?)");
			String like = "%" + q.trim() + "%";
			args.add(like);
			args.add(like);
			args.add(like);
		}
		String from_ = " from payment p join family f on f.id = p.family_id join ledger_account a on a.id = p.ledger_account_id"
				+ " left join student s on s.id = p.student_id";
		Long total = jdbc.queryForObject("select count(*)" + from_ + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<PaymentRow> rows = jdbc.query("""
				select p.id, p.receipt_no, p.payment_date, f.family_no, f.father_name, s.name, p.method, a.name, p.amount,
				       p.received_by, p.reverses_payment_id,
				       exists (select 1 from payment r where r.reverses_payment_id = p.id)""" + from_ + where
				+ " order by p.id desc limit ? offset ?", (rs, i) -> new PaymentRow(rs.getLong(1), rs.getString(2),
						rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
						rs.getString(8), Amounts.text(rs.getBigDecimal(9)), rs.getString(10), (Long) rs.getObject(11),
						rs.getBoolean(12)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	private long family(PaymentInput in) {
		if (in.studentId() != null) {
			List<Long> f = jdbc.queryForList("select family_id from student where id = ?", Long.class, in.studentId());
			if (f.isEmpty()) {
				throw ApiException.notFound("Student");
			}
			if (in.familyId() != null && !in.familyId().equals(f.getFirst())) {
				throw ApiException.businessRule("STUDENT_NOT_IN_FAMILY", "The student is not in this family");
			}
			return f.getFirst();
		}
		if (in.familyId() == null) {
			throw ApiException.businessRule("PAYER_REQUIRED", "Choose the family or student who is paying");
		}
		Long n = jdbc.queryForObject("select count(*) from family where id = ?", Long.class, in.familyId());
		if (n == null || n == 0) {
			throw ApiException.notFound("Family");
		}
		return in.familyId();
	}

	private static PaymentView withReplay(PaymentView v) {
		return new PaymentView(v.id(), v.receiptNo(), v.familyId(), v.familyNo(), v.fatherName(), v.studentId(),
				v.studentName(), v.paymentDate(), v.method(), v.accountId(), v.accountName(), v.amount(), v.reference(),
				v.remarks(), v.receivedBy(), v.createdAt(), v.reversesPaymentId(), v.reversalReason(),
				v.reversedByPaymentId(), v.creditRemaining(), v.familyBalance(), v.journalEntryId(), v.allocations(),
				true);
	}

	private static String hash(PaymentInput in) {
		StringBuilder canonical = new StringBuilder();
		canonical.append(in.familyId()).append('|').append(in.studentId()).append('|')
			.append(in.amount() == null ? "" : in.amount().trim()).append('|').append(in.paymentDate()).append('|')
			.append(in.method()).append('|').append(in.accountId()).append('|').append(in.reference()).append('|');
		if (in.allocations() != null) {
			in.allocations().forEach(a -> canonical.append(a.billLineId()).append('=').append(a.amount()).append(';'));
		}
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
