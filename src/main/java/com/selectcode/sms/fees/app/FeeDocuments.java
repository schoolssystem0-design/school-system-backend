package com.selectcode.sms.fees.app;

import com.selectcode.sms.fees.app.FeeQueries.BillLineView;
import com.selectcode.sms.fees.app.FeeQueries.BillView;
import com.selectcode.sms.fees.app.PaymentService.AllocationView;
import com.selectcode.sms.fees.app.PaymentService.PaymentView;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.YearContext;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Printed fee documents: vouchers (FR 10.6), receipts (FR 10.8) and the final reminder letter (FR 10.16). */
@Service
public class FeeDocuments {

	private static final Font SMALL = new Font(Font.HELVETICA, 7.5f);

	private static final Font SMALL_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD);

	private static final Font COPY = new Font(Font.HELVETICA, 7, Font.BOLD, new Color(90, 90, 90));

	private static final Font NAME = new Font(Font.HELVETICA, 10, Font.BOLD, new Color(20, 40, 90));

	private final FeeQueries queries;

	private final PaymentService payments;

	private final OpenLines openLines;

	private final SettingsService settings;

	private final LetterheadSource letterhead;

	private final YearContext yearContext;

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final Clock clock;

	FeeDocuments(FeeQueries queries, PaymentService payments, OpenLines openLines, SettingsService settings,
			LetterheadSource letterhead, YearContext yearContext, JdbcTemplate jdbc, AuditService audit, Clock clock) {
		this.queries = queries;
		this.payments = payments;
		this.openLines = openLines;
		this.settings = settings;
		this.letterhead = letterhead;
		this.yearContext = yearContext;
		this.jdbc = jdbc;
		this.audit = audit;
		this.clock = clock;
	}

	// --------------------------------------------------------------- vouchers

	/** Bills to print: explicit ids, a bill run, or the latest bill of every student in a section. */
	@Transactional(readOnly = true)
	public List<Long> billsFor(List<Long> billIds, Long runId, Long sectionId) {
		if (billIds != null && !billIds.isEmpty()) {
			return billIds;
		}
		long yearId = yearContext.requireSelected().id();
		if (runId != null) {
			return jdbc.queryForList("""
					select b.id from bill b left join school_class c on c.id = b.class_id
					left join section s on s.id = b.section_id join student st on st.id = b.student_id
					where b.fee_run_id = ? order by c.display_order, s.name, st.name""", Long.class, runId);
		}
		if (sectionId != null) {
			return jdbc.queryForList("""
					select distinct on (b.student_id) b.id from bill b join student st on st.id = b.student_id
					where b.section_id = ? and b.academic_year_id = ? and st.status = 'ACTIVE'
					order by b.student_id, b.id desc""", Long.class, sectionId, yearId);
		}
		throw ApiException.businessRule("BILLS_REQUIRED", "Choose the bills, a bill run or a section");
	}

	/** One A4 landscape page per bill with three copies: bank, school and parent. */
	@Transactional(readOnly = true)
	public byte[] vouchers(List<Long> billIds) {
		if (billIds.isEmpty()) {
			throw ApiException.businessRule("NO_BILLS", "There are no bills to print");
		}
		if (billIds.size() > 2000) {
			throw ApiException.businessRule("TOO_MANY", "Print at most 2,000 vouchers at a time");
		}
		Letterhead lh = letterhead.current();
		boolean showArrears = settings.bool(SettingKey.SHOW_ARREARS_ON_VOUCHER);
		boolean showDiscount = settings.bool(SettingKey.SHOW_DISCOUNT_ON_VOUCHER);
		boolean signature = settings.bool(SettingKey.SIGNATURE_ON_VOUCHER);
		BigDecimal lateFee = settings.money(SettingKey.LATE_FEE_AMOUNT).amount();
		String bank = settings.text(SettingKey.VOUCHER_BANK_DETAILS);
		String note = settings.text(SettingKey.VOUCHER_NOTE);
		List<VoucherData> data = new ArrayList<>();
		for (long id : billIds) {
			BillView bill = queries.bill(id);
			// Arrears: what the student still owes on earlier bills, counted once (QA-FEE-018).
			BigDecimal arrears = Amounts.nz(jdbc.queryForObject("""
					select sum(balance) from fee_line_status where student_id = ? and bill_id < ? and balance > 0""",
					BigDecimal.class, bill.studentId(), bill.id()));
			data.add(new VoucherData(bill, arrears));
		}
		return PdfKit.render(PageSize.A4.rotate(), 18, doc -> {
			boolean first = true;
			for (VoucherData v : data) {
				if (!first) {
					doc.newPage();
				}
				first = false;
				PdfPTable page = new PdfPTable(3);
				page.setWidthPercentage(100);
				for (String copy : List.of("BANK COPY", "SCHOOL COPY", "PARENT COPY")) {
					PdfPCell cell = new PdfPCell();
					cell.setPadding(6);
					cell.setBorderColor(new Color(160, 160, 160));
					voucherCopy(cell, copy, lh, v, showArrears, showDiscount, signature, lateFee, bank, note);
					page.addCell(cell);
				}
				PdfKit.add(doc, page);
			}
		});
	}

	record VoucherData(BillView bill, BigDecimal arrears) {
	}

	private void voucherCopy(PdfPCell cell, String copy, Letterhead lh, VoucherData v, boolean showArrears,
			boolean showDiscount, boolean signature, BigDecimal lateFee, String bank, String note) {
		BillView b = v.bill();
		Paragraph label = new Paragraph(copy, COPY);
		label.setAlignment(Element.ALIGN_RIGHT);
		cell.addElement(label);
		Paragraph name = new Paragraph(PdfKit.nz(lh.schoolName()), NAME);
		name.setAlignment(Element.ALIGN_CENTER);
		cell.addElement(name);
		if (lh.address() != null) {
			Paragraph addr = new Paragraph(lh.address(), SMALL);
			addr.setAlignment(Element.ALIGN_CENTER);
			cell.addElement(addr);
		}
		if (bank != null && !bank.isBlank()) {
			Paragraph bankLine = new Paragraph(bank, SMALL_BOLD);
			bankLine.setAlignment(Element.ALIGN_CENTER);
			bankLine.setSpacingAfter(4);
			cell.addElement(bankLine);
		}
		PdfPTable info = new PdfPTable(new float[] { 1.3f, 2f });
		info.setWidthPercentage(100);
		row(info, "Voucher no.", b.billNo());
		row(info, "Student", b.studentName());
		row(info, "GR no.", b.grNo());
		row(info, "Father", b.fatherName());
		row(info, "Class", b.className() == null ? "" : b.className() + " - " + PdfKit.nz(b.sectionName()));
		row(info, "Family no.", b.familyNo());
		row(info, "Issued", PdfKit.date(b.issueDate()));
		row(info, "Due date", PdfKit.date(b.dueDate()));
		row(info, "Valid until", PdfKit.date(b.validityDate()));
		cell.addElement(info);

		PdfPTable lines = new PdfPTable(new float[] { 3f, 1.4f });
		lines.setWidthPercentage(100);
		lines.setSpacingBefore(4);
		header(lines, "Fee", "Amount (Rs.)");
		BigDecimal current = BigDecimal.ZERO;
		for (BillLineView l : b.lines()) {
			BigDecimal charged = new BigDecimal(l.net()).add(new BigDecimal(l.adjusted()));
			if (showDiscount && new BigDecimal(l.discount()).signum() > 0) {
				amountRow(lines, l.description(), new BigDecimal(l.gross()), false);
				amountRow(lines, "   Less discount", new BigDecimal(l.discount()).negate(), false);
			}
			else {
				amountRow(lines, l.description(), new BigDecimal(l.net()), false);
			}
			if (new BigDecimal(l.adjusted()).signum() != 0) {
				amountRow(lines, "   Correction", new BigDecimal(l.adjusted()), false);
			}
			current = current.add(charged);
		}
		BigDecimal paid = new BigDecimal(b.paid());
		if (paid.signum() > 0) {
			amountRow(lines, "Already paid", paid.negate(), false);
		}
		BigDecimal thisBill = new BigDecimal(b.balance());
		amountRow(lines, "This month", thisBill, true);
		BigDecimal payable = thisBill.add(v.arrears());
		if (showArrears && v.arrears().signum() > 0) {
			amountRow(lines, "Arrears (earlier unpaid)", v.arrears(), false);
		}
		amountRow(lines, "Payable by due date", payable, true);
		if (lateFee.signum() > 0) {
			amountRow(lines, "Payable after due date", payable.add(lateFee), true);
		}
		cell.addElement(lines);
		Paragraph words = new Paragraph(PdfKit.amountInWords(payable), SMALL);
		words.setSpacingBefore(3);
		cell.addElement(words);
		if (note != null && !note.isBlank()) {
			Paragraph n = new Paragraph(note, SMALL);
			n.setSpacingBefore(3);
			cell.addElement(n);
		}
		Paragraph sign = new Paragraph(signature ? "\n\n______________\nAccounts office" : "\n\n______________\nBank stamp",
				SMALL);
		sign.setAlignment(Element.ALIGN_RIGHT);
		cell.addElement(sign);
	}

	private static void row(PdfPTable table, String label, String value) {
		PdfPCell l = new PdfPCell(new Phrase(label, SMALL_BOLD));
		l.setBorder(Rectangle.NO_BORDER);
		l.setPadding(1);
		PdfPCell v = new PdfPCell(new Phrase(PdfKit.nz(value), SMALL));
		v.setBorder(Rectangle.NO_BORDER);
		v.setPadding(1);
		table.addCell(l);
		table.addCell(v);
	}

	private static void header(PdfPTable table, String left, String right) {
		for (String text : List.of(left, right)) {
			PdfPCell c = new PdfPCell(new Phrase(text, SMALL_BOLD));
			c.setBackgroundColor(new Color(235, 237, 240));
			c.setPadding(2);
			if (text.equals(right)) {
				c.setHorizontalAlignment(Element.ALIGN_RIGHT);
			}
			table.addCell(c);
		}
	}

	private static void amountRow(PdfPTable table, String label, BigDecimal amount, boolean bold) {
		PdfPCell l = new PdfPCell(new Phrase(label, bold ? SMALL_BOLD : SMALL));
		l.setPadding(2);
		PdfPCell r = new PdfPCell(new Phrase(new java.text.DecimalFormat("#,##0.00").format(amount),
				bold ? SMALL_BOLD : SMALL));
		r.setPadding(2);
		r.setHorizontalAlignment(Element.ALIGN_RIGHT);
		table.addCell(l);
		table.addCell(r);
	}

	// ---------------------------------------------------------------- receipt

	@Transactional(readOnly = true)
	public byte[] receipt(long paymentId) {
		PaymentView p = payments.get(paymentId);
		if (p.reversesPaymentId() != null) {
			throw ApiException.businessRule("IS_REVERSAL", "Reversals have no receipt; print the original receipt");
		}
		Letterhead lh = letterhead.current();
		boolean a5 = "A5".equals(settings.text(SettingKey.RECEIPT_STYLE));
		return PdfKit.render(a5 ? PageSize.A5 : PageSize.A4, 28, doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, p.reversedByPaymentId() != null ? "FEE RECEIPT (REVERSED)" : "FEE RECEIPT");
			PdfKit.line(doc, "Receipt no. " + p.receiptNo(), "Date: " + PdfKit.date(p.paymentDate()));
			List<String[]> rows = new ArrayList<>();
			rows.add(new String[] { "Family", p.familyNo() + " - " + PdfKit.nz(p.fatherName()) });
			if (p.studentName() != null) {
				rows.add(new String[] { "Student", p.studentName() });
			}
			rows.add(new String[] { "Amount", Amounts.rupees(new BigDecimal(p.amount())) });
			rows.add(new String[] { "In words", PdfKit.amountInWords(new BigDecimal(p.amount())) });
			rows.add(new String[] { "Paid by", label(p.method()) + (p.reference() == null ? "" : " - " + p.reference()) });
			rows.add(new String[] { "Received into", p.accountName() });
			rows.add(new String[] { "Received by", PdfKit.nz(p.receivedBy()) });
			PdfKit.details(doc, rows);
			PdfPTable table = new PdfPTable(new float[] { 2.2f, 1f, 3f, 1.4f });
			table.setWidthPercentage(100);
			for (String h : List.of("Student", "GR no.", "Fee", "Amount (Rs.)")) {
				PdfPCell c = new PdfPCell(new Phrase(h, PdfKit.BODY_BOLD));
				c.setBackgroundColor(new Color(243, 244, 246));
				c.setPadding(4);
				table.addCell(c);
			}
			for (AllocationView a : p.allocations()) {
				if (!"PAYMENT".equals(a.kind()) && !"CREDIT".equals(a.kind())) {
					continue;
				}
				for (String text : List.of(a.studentName(), a.grNo(), a.description())) {
					PdfPCell c = new PdfPCell(new Phrase(text, PdfKit.BODY));
					c.setPadding(4);
					table.addCell(c);
				}
				PdfPCell amount = new PdfPCell(new Phrase(new java.text.DecimalFormat("#,##0.00").format(new BigDecimal(a.amount())),
						PdfKit.BODY));
				amount.setPadding(4);
				amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
				table.addCell(amount);
			}
			table.setSpacingAfter(10);
			PdfKit.add(doc, table);
			BigDecimal advance = new BigDecimal(p.creditRemaining());
			if (advance.signum() > 0) {
				PdfKit.paragraph(doc, "Kept as advance for future fees: " + Amounts.rupees(advance));
			}
			BigDecimal balance = new BigDecimal(p.familyBalance());
			PdfKit.paragraph(doc, balance.signum() > 0 ? "Balance still due (whole family): " + Amounts.rupees(balance)
					: "No dues remaining for the family.");
			PdfKit.signatures(doc, lh, List.of("Accountant"), true);
		});
	}

	private static String label(String method) {
		return switch (method) {
			case "CASH" -> "Cash";
			case "BANK" -> "Bank deposit";
			case "CHEQUE" -> "Cheque";
			default -> "Online transfer";
		};
	}

	// ----------------------------------------------------- final reminder letter

	/** A formal final reminder for a family with overdue fees (FR 10.16). Recorded as a follow-up. */
	@Transactional
	public byte[] finalReminder(long familyId) {
		Map<String, Object> family = jdbc.queryForList(
				"select family_no, father_name, address from family where id = ?", familyId).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Family"));
		List<OpenLines.OpenLine> lines = openLines.forFamily(familyId, null);
		BigDecimal owed = openLines.owed(familyId);
		BigDecimal credit = openLines.creditTotal(familyId);
		BigDecimal due = owed.subtract(credit);
		if (due.signum() <= 0) {
			throw ApiException.businessRule("NOTHING_DUE", "This family has no dues");
		}
		LocalDate today = Amounts.today(clock);
		LocalDate payBy = today.plusDays(7);
		Letterhead lh = letterhead.current();
		byte[] pdf = PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.line(doc, "Family no. " + family.get("family_no"), "Date: " + PdfKit.date(today));
			PdfKit.title(doc, "FINAL REMINDER: OUTSTANDING FEES");
			PdfKit.paragraph(doc, "Dear " + PdfKit.nz((String) family.get("father_name")) + ",");
			PdfKit.paragraph(doc, "Despite earlier reminders, the following fees remain unpaid. The total outstanding is "
					+ Amounts.rupees(due) + " (" + PdfKit.amountInWords(due) + ").");
			PdfPTable table = new PdfPTable(new float[] { 2.2f, 3f, 1.4f, 1.4f });
			table.setWidthPercentage(100);
			for (String h : List.of("Student", "Fee", "Due date", "Amount (Rs.)")) {
				PdfPCell c = new PdfPCell(new Phrase(h, PdfKit.BODY_BOLD));
				c.setBackgroundColor(new Color(243, 244, 246));
				c.setPadding(4);
				table.addCell(c);
			}
			for (OpenLines.OpenLine l : lines) {
				for (String text : List.of(l.studentName(), l.description(), PdfKit.date(l.dueDate()))) {
					PdfPCell c = new PdfPCell(new Phrase(text, PdfKit.BODY));
					c.setPadding(4);
					table.addCell(c);
				}
				PdfPCell amount = new PdfPCell(new Phrase(l.balance(), PdfKit.BODY));
				amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
				amount.setPadding(4);
				table.addCell(amount);
			}
			table.setSpacingAfter(12);
			PdfKit.add(doc, table);
			if (credit.signum() > 0) {
				PdfKit.paragraph(doc, "An advance of " + Amounts.rupees(credit) + " held by the school has been deducted.");
			}
			PdfKit.paragraph(doc, "Please clear the outstanding amount by " + PdfKit.date(payBy)
					+ ". If you have already paid, please bring the receipt to the accounts office. "
					+ "If you are facing difficulty, please meet the Principal to discuss an arrangement.");
			PdfKit.signatures(doc, lh, List.of("Accountant", "Principal"), true);
		});
		jdbc.update("""
				insert into fee_followup (school_id, family_id, remark, next_date, created_by)
				values (?, ?, ?, ?, ?)""", com.selectcode.sms.shared.tenant.TenantContext.requireSchoolId(), familyId,
				"Final reminder letter issued for " + Amounts.rupees(due), java.sql.Date.valueOf(payBy),
				com.selectcode.sms.shared.security.CurrentActor.require().displayName());
		audit.record("FEE_FINAL_REMINDER", "family", familyId, null, Map.of("amount", due.toPlainString()));
		return pdf;
	}
}
