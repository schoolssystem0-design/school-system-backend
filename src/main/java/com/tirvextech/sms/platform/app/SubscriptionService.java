package com.tirvextech.sms.platform.app;

import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.messaging.MessageSender;
import com.tirvextech.sms.shared.messaging.OutgoingMessage;
import com.tirvextech.sms.shared.pdf.Letterhead;
import com.tirvextech.sms.shared.pdf.PdfKit;
import com.tirvextech.sms.shared.tenant.TenantContext;
import com.tirvextech.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tirvex Tech subscriptions: each school pays monthly or yearly (no student limit) at the price agreed with it.
 * <ul>
 * <li>An invoice is issued 7 days before each period starts and emailed to the school's main administrator.</li>
 * <li>Reminders go out on the due date; if it is still unpaid {@code grace days} after it, the school's access pauses
 * (its users cannot sign in, data is kept) until the owner marks the invoice paid.</li>
 * <li>Payments are made by bank transfer, Raast or cash and recorded by the owner (no payment gateway, no fees).</li>
 * </ul>
 */
@Service
public class SubscriptionService {

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	static final int ISSUE_DAYS_BEFORE = 7;

	private static final Set<String> CYCLES = Set.of("MONTHLY", "YEARLY");

	public record SubscriptionInput(String cycle, String amount, LocalDate startOn, Integer graceDays, String notes) {
	}

	public record SubscriptionView(long id, long schoolId, String schoolCode, String schoolName, String cycle,
			String amount, LocalDate startedOn, LocalDate nextDueOn, int graceDays, String status, String notes,
			String standing, String unpaidAmount, LocalDate pausesOn) {
	}

	public record InvoiceView(long id, String invoiceNo, Long schoolId, String schoolCode, String schoolName,
			String cycle, LocalDate periodStart, LocalDate periodEnd, String amount, LocalDate issuedOn, LocalDate dueOn,
			LocalDate pausesOn, String status, boolean overdue, boolean paused, LocalDate paidOn, String paymentMethod,
			String paymentReference, String voidReason) {
	}

	public record BillingSummary(int payingSchools, int monthly, int yearly, String expectedThisMonth,
			String receivedThisMonth, String unpaid, String overdue, int pausedSchools) {
	}

	public record BillingOverview(BillingSummary summary, List<SubscriptionView> subscriptions, List<InvoiceView> invoices,
			String paymentDetails) {
	}

	public record SchoolBilling(SubscriptionView subscription, List<InvoiceView> invoices) {
	}

	/** What a school's own users see: whether something is due, and (for administrators) the invoices. */
	public record OwnSubscription(String standing, String cycle, String amount, LocalDate nextDueOn,
			List<InvoiceView> openInvoices, String paymentDetails) {
	}

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final AuditService audit;

	private final MessageSender messages;

	private final Clock clock;

	SubscriptionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AuditService audit,
			MessageSender messages, Clock clock) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.audit = audit;
		this.messages = messages;
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}

	// ---------------------------------------------------------------- owner settings

	public String paymentDetails() {
		return jdbc.query("select value from platform_setting where key = 'payment_details'",
				rs -> rs.next() ? rs.getString(1) : "");
	}

	public String setPaymentDetails(String text) {
		String value = text == null ? "" : text.trim();
		if (value.length() > 1000) {
			throw ApiException.businessRule("TEXT_TOO_LONG", "Keep the payment details under 1,000 characters");
		}
		jdbc.update("""
				insert into platform_setting (key, value, updated_at) values ('payment_details', ?, now())
				on conflict (key) do update set value = excluded.value, updated_at = now()""", value);
		audit.recordPlatform("BILLING_DETAILS_CHANGED", null, null);
		return value;
	}

	// ---------------------------------------------------------------- subscriptions

	/**
	 * Starts a school's subscription (ending its free trial) or changes its plan. A new or restarted subscription
	 * issues its first invoice at once if the start is within a week.
	 */
	public SchoolBilling saveSubscription(long schoolId, SubscriptionInput in) {
		String cycle = in.cycle() == null ? "" : in.cycle().trim().toUpperCase(Locale.ROOT);
		if (!CYCLES.contains(cycle)) {
			throw ApiException.businessRule("CYCLE_INVALID", "Choose monthly or yearly");
		}
		BigDecimal amount = amount(in.amount());
		int grace = in.graceDays() == null ? 10 : in.graceDays();
		if (grace < 0 || grace > 60) {
			throw ApiException.businessRule("GRACE_INVALID", "Grace days must be 0 to 60");
		}
		String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
		TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			Map<String, Object> school = jdbc.queryForList("select id, trial_ends_at from school where id = ?", schoolId)
				.stream().findFirst().orElseThrow(() -> ApiException.notFound("School"));
			List<Map<String, Object>> existing = jdbc.queryForList(
					"select id, status from subscription where subscribed_school_id = ?", schoolId);
			if (existing.isEmpty() || "CANCELLED".equals(existing.getFirst().get("status"))) {
				LocalDate start = in.startOn() == null ? today() : in.startOn();
				if (existing.isEmpty()) {
					jdbc.update("""
							insert into subscription (subscribed_school_id, cycle, amount, started_on, next_due_on, grace_days, notes)
							values (?, ?, ?, ?, ?, ?, ?)""", schoolId, cycle, amount, Date.valueOf(start), Date.valueOf(start),
							grace, notes);
				}
				else {
					jdbc.update("""
							update subscription set cycle = ?, amount = ?, started_on = ?, next_due_on = ?, grace_days = ?,
							       notes = ?, status = 'ACTIVE', updated_at = now() where subscribed_school_id = ?""", cycle,
							amount, Date.valueOf(start), Date.valueOf(start), grace, notes, schoolId);
				}
				if (school.get("trial_ends_at") != null) {
					jdbc.update("update school set trial_ends_at = null, updated_at = now() where id = ?", schoolId);
					audit.recordPlatform("TRIAL_CONVERTED", schoolId, null);
				}
				audit.recordPlatform("SUBSCRIPTION_STARTED", schoolId,
						Map.of("cycle", cycle, "amount", amount.toPlainString(), "startOn", start.toString()));
			}
			else {
				jdbc.update("""
						update subscription set cycle = ?, amount = ?, grace_days = ?, notes = ?, updated_at = now()
						where subscribed_school_id = ?""", cycle, amount, grace, notes, schoolId);
				audit.recordPlatform("SUBSCRIPTION_CHANGED", schoolId, Map.of("cycle", cycle, "amount", amount.toPlainString()));
			}
			return null;
		}));
		issueDue(today(), schoolId);
		return schoolBilling(schoolId);
	}

	/** Stops new invoices; invoices already issued stay as they are. */
	public SchoolBilling cancel(long schoolId) {
		TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			int n = jdbc.update("""
					update subscription set status = 'CANCELLED', updated_at = now()
					where subscribed_school_id = ? and status = 'ACTIVE'""", schoolId);
			if (n == 0) {
				throw ApiException.businessRule("NO_SUBSCRIPTION", "This school has no active subscription");
			}
			audit.recordPlatform("SUBSCRIPTION_CANCELLED", schoolId, null);
			return null;
		}));
		return schoolBilling(schoolId);
	}

	// ---------------------------------------------------------------- invoices

	/** Issues every invoice that is due within a week (catching up if the server was off). @return invoices issued */
	public int issueDue(LocalDate today, Long onlySchool) {
		List<Long> issued = TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('platform-invoice'))", Object.class);
			List<Long> ids = new ArrayList<>();
			List<Map<String, Object>> due = jdbc.queryForList("""
					select su.id, su.subscribed_school_id, su.cycle, su.amount, su.next_due_on, su.grace_days, s.code, s.name
					from subscription su join school s on s.id = su.subscribed_school_id
					where su.status = 'ACTIVE' and su.next_due_on <= ?""" + (onlySchool == null ? "" : " and su.subscribed_school_id = ?"),
					onlySchool == null ? new Object[] { Date.valueOf(today.plusDays(ISSUE_DAYS_BEFORE)) }
							: new Object[] { Date.valueOf(today.plusDays(ISSUE_DAYS_BEFORE)), onlySchool });
			for (Map<String, Object> su : due) {
				LocalDate start = ((Date) su.get("next_due_on")).toLocalDate();
				String cycle = (String) su.get("cycle");
				// At most a year of catching up at once
				for (int n = 0; n < 13 && !start.isAfter(today.plusDays(ISSUE_DAYS_BEFORE)); n++) {
					LocalDate next = "YEARLY".equals(cycle) ? start.plusYears(1) : start.plusMonths(1);
					String number = nextInvoiceNo(start);
					Long id = jdbc.queryForObject("""
							insert into platform_invoice (invoice_no, billed_school_id, school_code, school_name, subscription_id,
							       cycle, period_start, period_end, amount, issued_on, due_on, grace_days)
							values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (subscription_id, period_start) do nothing
							returning id""", Long.class, number, su.get("subscribed_school_id"), su.get("code"), su.get("name"),
							su.get("id"), cycle, Date.valueOf(start), Date.valueOf(next.minusDays(1)), su.get("amount"),
							Date.valueOf(today), Date.valueOf(start), su.get("grace_days"));
					if (id != null) {
						ids.add(id);
					}
					start = next;
				}
				jdbc.update("update subscription set next_due_on = ?, updated_at = now() where id = ?", Date.valueOf(start),
						su.get("id"));
			}
			return ids;
		}));
		for (long id : issued) {
			notifyIssued(id);
		}
		return issued.size();
	}

	private String nextInvoiceNo(LocalDate periodStart) {
		String prefix = "INV-" + periodStart.getYear() + "-";
		Integer last = jdbc.queryForObject("""
				select coalesce(max(substring(invoice_no from length(?) + 1)::int), 0) from platform_invoice
				where invoice_no like ?""", Integer.class, prefix, prefix + "%");
		return prefix + String.format("%04d", (last == null ? 0 : last) + 1);
	}

	public InvoiceView markPaid(long invoiceId, LocalDate paidOn, String method, String reference) {
		String how = method == null || method.isBlank() ? "Bank transfer" : method.trim();
		InvoiceView paid = TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			InvoiceView invoice = invoice(invoiceId);
			if (!"UNPAID".equals(invoice.status())) {
				throw ApiException.businessRule("INVOICE_NOT_UNPAID", "Invoice " + invoice.invoiceNo() + " is " + invoice.status().toLowerCase(Locale.ROOT));
			}
			LocalDate on = paidOn == null ? today() : paidOn;
			if (on.isAfter(today())) {
				throw ApiException.businessRule("DATE_INVALID", "The payment date cannot be in the future");
			}
			jdbc.update("""
					update platform_invoice set status = 'PAID', paid_on = ?, payment_method = ?, payment_reference = ?,
					       updated_at = now() where id = ?""", Date.valueOf(on), truncate(how, 30),
					reference == null || reference.isBlank() ? null : truncate(reference.trim(), 100), invoiceId);
			audit.recordPlatform("INVOICE_PAID", invoice.schoolId(),
					Map.of("invoice", invoice.invoiceNo(), "amount", invoice.amount(), "method", how));
			return invoice(invoiceId);
		}));
		if (paid.schoolId() != null) {
			email(paid.schoolId(), "Payment received: " + paid.invoiceNo(), """
					Thank you. We have received %s for invoice %s (%s to %s) on %s.

					Tirvex Tech
					""".formatted(rs(paid.amount()), paid.invoiceNo(), PdfKit.date(paid.periodStart()),
					PdfKit.date(paid.periodEnd()), PdfKit.date(paid.paidOn())));
		}
		return paid;
	}

	public InvoiceView voidInvoice(long invoiceId, String reason) {
		String why = reason == null ? "" : reason.trim();
		if (why.isEmpty()) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for cancelling the invoice");
		}
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			InvoiceView invoice = invoice(invoiceId);
			if (!"UNPAID".equals(invoice.status())) {
				throw ApiException.businessRule("INVOICE_NOT_UNPAID", "Only an unpaid invoice can be cancelled");
			}
			jdbc.update("update platform_invoice set status = 'VOID', void_reason = ?, updated_at = now() where id = ?",
					truncate(why, 500), invoiceId);
			audit.recordPlatform("INVOICE_VOIDED", invoice.schoolId(), Map.of("invoice", invoice.invoiceNo(), "reason", why));
			return invoice(invoiceId);
		}));
	}

	/** Due-date and access-paused emails (once each). @return emails sent */
	public int sendNotices(LocalDate today) {
		List<Map<String, Object>> dueToday = TenantContext.callAcrossAllSchools(() -> jdbc.queryForList("""
				select id from platform_invoice where status = 'UNPAID' and due_on <= ? and due_notice_sent_at is null
				  and billed_school_id is not null""", Date.valueOf(today)));
		int sent = 0;
		for (Map<String, Object> row : dueToday) {
			InvoiceView i = TenantContext.callAcrossAllSchools(() -> invoice(((Number) row.get("id")).longValue()));
			email(i.schoolId(), "Payment due today: " + i.invoiceNo(), """
					Invoice %s for %s is due today: %s for %s to %s.

					Please pay to keep using the school system. If it is not paid by %s, access will pause until it is.

					How to pay:
					%s

					Tirvex Tech
					""".formatted(i.invoiceNo(), i.schoolName(), rs(i.amount()), PdfKit.date(i.periodStart()),
					PdfKit.date(i.periodEnd()), PdfKit.date(i.pausesOn()), paymentDetailsOrDefault()));
			TenantContext.callAcrossAllSchools(() -> jdbc.update("update platform_invoice set due_notice_sent_at = now() where id = ?", i.id()));
			sent++;
		}
		List<Map<String, Object>> paused = TenantContext.callAcrossAllSchools(() -> jdbc.queryForList("""
				select id from platform_invoice where status = 'UNPAID' and due_on + grace_days < ?
				  and paused_notice_sent_at is null and billed_school_id is not null""", Date.valueOf(today)));
		for (Map<String, Object> row : paused) {
			InvoiceView i = TenantContext.callAcrossAllSchools(() -> invoice(((Number) row.get("id")).longValue()));
			email(i.schoolId(), "Access paused: invoice " + i.invoiceNo() + " is unpaid", """
					Invoice %s (%s) was due on %s and is still unpaid, so access to the school system is paused.
					All your data is kept. Access returns as soon as the payment is received.

					How to pay:
					%s

					Tirvex Tech
					""".formatted(i.invoiceNo(), rs(i.amount()), PdfKit.date(i.dueOn()), paymentDetailsOrDefault()));
			TenantContext.callAcrossAllSchools(() -> jdbc.update("update platform_invoice set paused_notice_sent_at = now() where id = ?", i.id()));
			sent++;
		}
		return sent;
	}

	private void notifyIssued(long invoiceId) {
		InvoiceView i = TenantContext.callAcrossAllSchools(() -> invoice(invoiceId));
		email(i.schoolId(), "Invoice " + i.invoiceNo() + " for " + i.schoolName(), """
				Your %s subscription invoice is ready.

				Invoice: %s
				Period: %s to %s
				Amount: %s
				Due on: %s

				How to pay:
				%s

				Administrators can also see and download it in the school system (Reports, Subscription).

				Tirvex Tech
				""".formatted("YEARLY".equals(i.cycle()) ? "yearly" : "monthly", i.invoiceNo(), PdfKit.date(i.periodStart()),
				PdfKit.date(i.periodEnd()), rs(i.amount()), PdfKit.date(i.dueOn()), paymentDetailsOrDefault()));
		TenantContext.callAcrossAllSchools(() -> jdbc.update("update platform_invoice set issued_notice_sent_at = now() where id = ?", invoiceId));
	}

	/** To the school's main administrators (their email is on their account). */
	private void email(Long schoolId, String subject, String body) {
		if (schoolId == null) {
			return;
		}
		List<String> to = TenantContext.callAcrossAllSchools(() -> jdbc.queryForList("""
				select email from app_user where school_id = ? and is_main_admin and status = 'ACTIVE' and email is not null""",
				String.class, schoolId));
		to.forEach(address -> messages.send(OutgoingMessage.email(address, subject, body)));
	}

	private String paymentDetailsOrDefault() {
		String details = paymentDetails();
		return details.isBlank() ? "Contact Tirvex Tech for the payment details." : details;
	}

	// ---------------------------------------------------------------- views

	public BillingOverview overview() {
		LocalDate today = today();
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			List<SubscriptionView> subs = subscriptions(null, today);
			List<InvoiceView> invoices = invoices("true", List.of(), today);
			LocalDate monthStart = today.withDayOfMonth(1);
			LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
			BigDecimal expected = sum(invoices.stream().filter(i -> !"VOID".equals(i.status())
					&& !i.dueOn().isBefore(monthStart) && !i.dueOn().isAfter(monthEnd)).map(InvoiceView::amount).toList());
			BigDecimal received = sum(invoices.stream().filter(i -> "PAID".equals(i.status()) && i.paidOn() != null
					&& !i.paidOn().isBefore(monthStart) && !i.paidOn().isAfter(monthEnd)).map(InvoiceView::amount).toList());
			BigDecimal unpaid = sum(invoices.stream().filter(i -> "UNPAID".equals(i.status())).map(InvoiceView::amount).toList());
			BigDecimal overdue = sum(invoices.stream().filter(InvoiceView::overdue).map(InvoiceView::amount).toList());
			int pausedSchools = (int) invoices.stream().filter(InvoiceView::paused).map(InvoiceView::schoolId).distinct().count();
			List<SubscriptionView> active = subs.stream().filter(s -> "ACTIVE".equals(s.status())).toList();
			BillingSummary summary = new BillingSummary(active.size(),
					(int) active.stream().filter(s -> "MONTHLY".equals(s.cycle())).count(),
					(int) active.stream().filter(s -> "YEARLY".equals(s.cycle())).count(), money(expected), money(received),
					money(unpaid), money(overdue), pausedSchools);
			return new BillingOverview(summary, subs, invoices, paymentDetails());
		}));
	}

	public SchoolBilling schoolBilling(long schoolId) {
		LocalDate today = today();
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			List<SubscriptionView> subs = subscriptions(schoolId, today);
			return new SchoolBilling(subs.isEmpty() ? null : subs.getFirst(),
					invoices("i.billed_school_id = ?", List.of(schoolId), today));
		}));
	}

	/** For the school's own users: the banner and, for administrators, the open invoices. */
	public OwnSubscription own(long schoolId, boolean withInvoices) {
		LocalDate today = today();
		return TenantContext.callAcrossAllSchools(() -> transaction.execute(tx -> {
			List<SubscriptionView> subs = subscriptions(schoolId, today);
			List<InvoiceView> open = invoices("i.billed_school_id = ? and i.status = 'UNPAID'", List.of(schoolId), today);
			String standing = open.stream().anyMatch(InvoiceView::paused) ? "PAUSED"
					: open.stream().anyMatch(InvoiceView::overdue) ? "OVERDUE" : open.isEmpty() ? (subs.isEmpty() ? "NONE" : "PAID_UP") : "DUE";
			SubscriptionView s = subs.isEmpty() ? null : subs.getFirst();
			// Administrators see the open invoices with amounts; everyone else only the dates (for the banner)
			List<InvoiceView> shown = withInvoices ? open
					: open.stream().limit(1).map(i -> new InvoiceView(i.id(), i.invoiceNo(), null, null, null, i.cycle(),
							i.periodStart(), i.periodEnd(), null, i.issuedOn(), i.dueOn(), i.pausesOn(), i.status(), i.overdue(),
							i.paused(), null, null, null, null)).toList();
			return new OwnSubscription(standing, s == null ? null : s.cycle(), withInvoices && s != null ? s.amount() : null,
					s == null ? null : s.nextDueOn(), shown, withInvoices ? paymentDetailsOrDefault() : null);
		}));
	}

	/** True when the school has an invoice unpaid past its due date plus the grace days (its access is paused). */
	public static final String PAUSED_SQL = """
			exists (select 1 from platform_invoice pi where pi.billed_school_id = %s and pi.status = 'UNPAID'
			        and pi.due_on + pi.grace_days < (now() at time zone 'Asia/Karachi')::date)""";

	public boolean paused(long schoolId) {
		return Boolean.TRUE.equals(TenantContext.callAcrossAllSchools(
				() -> jdbc.queryForObject("select " + PAUSED_SQL.formatted("?"), Boolean.class, schoolId)));
	}

	private List<SubscriptionView> subscriptions(Long schoolId, LocalDate today) {
		return jdbc.query("""
				select su.id, su.subscribed_school_id, s.code, s.name, su.cycle, su.amount, su.started_on, su.next_due_on,
				       su.grace_days, su.status, su.notes,
				       (select coalesce(sum(i.amount), 0) from platform_invoice i where i.subscription_id = su.id and i.status = 'UNPAID'),
				       (select min(i.due_on) from platform_invoice i where i.subscription_id = su.id and i.status = 'UNPAID'),
				       (select min(i.due_on + i.grace_days) from platform_invoice i where i.subscription_id = su.id and i.status = 'UNPAID')
				from subscription su join school s on s.id = su.subscribed_school_id
				where (?::bigint is null or su.subscribed_school_id = ?) order by s.name""", (rs, n) -> {
			BigDecimal unpaid = rs.getBigDecimal(12);
			LocalDate firstDue = rs.getDate(13) == null ? null : rs.getDate(13).toLocalDate();
			LocalDate pauses = rs.getDate(14) == null ? null : rs.getDate(14).toLocalDate();
			String standing = "CANCELLED".equals(rs.getString(10)) && unpaid.signum() == 0 ? "CANCELLED"
					: pauses != null && today.isAfter(pauses) ? "PAUSED"
					: firstDue != null && today.isAfter(firstDue) ? "OVERDUE" : unpaid.signum() > 0 ? "DUE" : "PAID_UP";
			return new SubscriptionView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
					money(rs.getBigDecimal(6)), rs.getDate(7).toLocalDate(), rs.getDate(8).toLocalDate(), rs.getInt(9),
					rs.getString(10), rs.getString(11), standing, money(unpaid), unpaid.signum() > 0 ? pauses : null);
		}, schoolId, schoolId);
	}

	private List<InvoiceView> invoices(String where, List<Object> args, LocalDate today) {
		return jdbc.query("""
				select i.id, i.invoice_no, i.billed_school_id, i.school_code, i.school_name, i.cycle, i.period_start,
				       i.period_end, i.amount, i.issued_on, i.due_on, i.grace_days, i.status, i.paid_on, i.payment_method,
				       i.payment_reference, i.void_reason
				from platform_invoice i where\s""" + where + " order by i.due_on desc, i.id desc limit 500", (rs, n) -> {
			LocalDate due = rs.getDate(11).toLocalDate();
			LocalDate pauses = due.plusDays(rs.getInt(12));
			boolean unpaid = "UNPAID".equals(rs.getString(13));
			return new InvoiceView(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5),
					rs.getString(6), rs.getDate(7).toLocalDate(), rs.getDate(8).toLocalDate(), money(rs.getBigDecimal(9)),
					rs.getDate(10).toLocalDate(), due, pauses, rs.getString(13), unpaid && today.isAfter(due),
					unpaid && today.isAfter(pauses), rs.getDate(14) == null ? null : rs.getDate(14).toLocalDate(),
					rs.getString(15), rs.getString(16), rs.getString(17));
		}, args.toArray());
	}

	InvoiceView invoice(long id) {
		List<InvoiceView> found = invoices("i.id = ?", List.of(id), today());
		if (found.isEmpty()) {
			throw ApiException.notFound("Invoice");
		}
		return found.getFirst();
	}

	/** An invoice of this school (for its own administrators), or not found. */
	public InvoiceView ownInvoice(long schoolId, long invoiceId) {
		InvoiceView i = TenantContext.callAcrossAllSchools(() -> invoice(invoiceId));
		if (i.schoolId() == null || i.schoolId() != schoolId) {
			throw ApiException.notFound("Invoice");
		}
		return i;
	}

	// ---------------------------------------------------------------- PDF

	public byte[] pdf(long invoiceId) {
		InvoiceView i = TenantContext.callAcrossAllSchools(() -> invoice(invoiceId));
		String details = paymentDetailsOrDefault();
		byte[] logo = brandLogo();
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, new Letterhead("Tirvex Tech", null, null, null, null, null, null, logo, null, List.of()));
			PdfKit.title(doc, "PAID".equals(i.status()) ? "INVOICE (PAID)" : "VOID".equals(i.status()) ? "INVOICE (CANCELLED)" : "INVOICE");
			List<String[]> rows = new ArrayList<>();
			rows.add(new String[] { "Invoice no.", i.invoiceNo() });
			rows.add(new String[] { "School", i.schoolName() + " (" + i.schoolCode() + ")" });
			rows.add(new String[] { "Plan", ("YEARLY".equals(i.cycle()) ? "Yearly" : "Monthly") + " subscription, all students" });
			rows.add(new String[] { "Period", PdfKit.date(i.periodStart()) + " to " + PdfKit.date(i.periodEnd()) });
			rows.add(new String[] { "Issued on", PdfKit.date(i.issuedOn()) });
			rows.add(new String[] { "Due on", PdfKit.date(i.dueOn()) });
			rows.add(new String[] { "Amount", rs(i.amount()) });
			if ("PAID".equals(i.status())) {
				rows.add(new String[] { "Paid on", PdfKit.date(i.paidOn()) + " by " + i.paymentMethod()
						+ (i.paymentReference() == null ? "" : " (" + i.paymentReference() + ")") });
			}
			if ("VOID".equals(i.status())) {
				rows.add(new String[] { "Cancelled", i.voidReason() });
			}
			PdfKit.details(doc, rows);
			if ("UNPAID".equals(i.status())) {
				PdfKit.paragraph(doc, "How to pay:\n" + details);
				PdfKit.paragraph(doc, "If this invoice is not paid by " + PdfKit.date(i.pausesOn())
						+ ", access to the school system pauses until it is paid. Your data is always kept.");
			}
			PdfKit.paragraph(doc, "Thank you for using the Tirvex Tech School Management System.");
		});
	}

	private static byte[] brandLogo() {
		try (var in = SubscriptionService.class.getResourceAsStream("/brand/tirvex-logo.png")) {
			return in == null ? null : in.readAllBytes();
		}
		catch (java.io.IOException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- money

	private static BigDecimal amount(String raw) {
		try {
			BigDecimal value = new BigDecimal(raw == null ? "" : raw.trim().replace(",", "")).setScale(2, RoundingMode.UNNECESSARY);
			if (value.signum() <= 0 || value.compareTo(new BigDecimal("100000000")) >= 0) {
				throw new NumberFormatException();
			}
			return value;
		}
		catch (NumberFormatException | ArithmeticException e) {
			throw ApiException.businessRule("AMOUNT_INVALID", "Enter an amount like 5000 or 5000.50");
		}
	}

	private static String money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private static BigDecimal sum(List<String> amounts) {
		return amounts.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	static String rs(String amount) {
		NumberFormat f = NumberFormat.getNumberInstance(Locale.ENGLISH);
		f.setMinimumFractionDigits(0);
		f.setMaximumFractionDigits(2);
		return "Rs. " + f.format(new BigDecimal(amount));
	}

	private static String truncate(String text, int max) {
		return text.length() > max ? text.substring(0, max) : text;
	}
}
