package com.tirvextech.sms.fees.app;

import com.tirvextech.sms.fees.app.FeeQueries.BillLineView;
import com.tirvextech.sms.fees.app.FeeQueries.BillView;
import com.tirvextech.sms.fees.app.PaymentService.AllocationView;
import com.tirvextech.sms.fees.app.PaymentService.PaymentView;
import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.pdf.Letterhead;
import com.tirvextech.sms.shared.pdf.LetterheadSource;
import com.tirvextech.sms.shared.pdf.PdfKit;
import com.tirvextech.sms.shared.verify.DocumentVerificationService;
import com.tirvextech.sms.shared.verify.DocumentVerificationService.DocKind;
import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.year.YearContext;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Chunk;
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

	private static final Font SMALL = new Font(Font.HELVETICA, 8.5f);

	private static final Font SMALL_BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD);

	private static final Font SMALL_LABEL = new Font(Font.HELVETICA, 7.8f, Font.BOLD, new Color(70, 75, 88));

	private static final Font SMALL_MUTED = new Font(Font.HELVETICA, 7.8f, Font.NORMAL, new Color(100, 104, 115));

	private static final Font WORDS = new Font(Font.HELVETICA, 8f, Font.ITALIC, new Color(60, 64, 75));

	private final FeeQueries queries;

	private final PaymentService payments;

	private final OpenLines openLines;

	private final SettingsService settings;

	private final LetterheadSource letterhead;

	private final YearContext yearContext;

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final Clock clock;

	private final DocumentVerificationService verification;

	FeeDocuments(FeeQueries queries, PaymentService payments, OpenLines openLines, SettingsService settings,
			LetterheadSource letterhead, YearContext yearContext, JdbcTemplate jdbc, AuditService audit, Clock clock,
			DocumentVerificationService verification) {
		this.queries = queries;
		this.payments = payments;
		this.openLines = openLines;
		this.settings = settings;
		this.letterhead = letterhead;
		this.yearContext = yearContext;
		this.jdbc = jdbc;
		this.audit = audit;
		this.clock = clock;
		this.verification = verification;
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

	/** One page per bill with the copies side by side (bank, school, parent by default; see the setting). */
	@Transactional(readOnly = true)
	public byte[] vouchers(List<Long> billIds) {
		if (billIds.isEmpty()) {
			throw ApiException.businessRule("NO_BILLS", "There are no bills to print");
		}
		if (billIds.size() > 2000) {
			throw ApiException.businessRule("TOO_MANY", "Print at most 2,000 vouchers at a time");
		}
		Letterhead lh = letterhead.current();
		VoucherStyle style = new VoucherStyle(settings.bool(SettingKey.SHOW_ARREARS_ON_VOUCHER),
				settings.bool(SettingKey.SHOW_DISCOUNT_ON_VOUCHER), settings.bool(SettingKey.SIGNATURE_ON_VOUCHER),
				settings.money(SettingKey.LATE_FEE_AMOUNT).amount(), settings.text(SettingKey.VOUCHER_BANK_DETAILS),
				settings.text(SettingKey.VOUCHER_NOTE), settings.bool(SettingKey.VOUCHER_LOGO), lh.style().accent());
		List<String> copies = switch (settings.text(SettingKey.VOUCHER_COPIES)) {
			case "Parent only" -> List.of("PARENT COPY");
			case "School and parent" -> List.of("SCHOOL COPY", "PARENT COPY");
			default -> List.of("BANK COPY", "SCHOOL COPY", "PARENT COPY");
		};
		List<VoucherData> data = new ArrayList<>();
		for (long id : billIds) {
			BillView bill = queries.bill(id);
			// Arrears: what the student still owes on earlier bills, counted once (QA-FEE-018).
			BigDecimal arrears = Amounts.nz(jdbc.queryForObject("""
					select sum(balance) from fee_line_status where student_id = ? and bill_id < ? and balance > 0""",
					BigDecimal.class, bill.studentId(), bill.id()));
			data.add(new VoucherData(bill, arrears));
		}
		boolean single = copies.size() == 1;
		return PdfKit.render(single ? PageSize.A5 : PageSize.A4.rotate(), single ? 22 : 16, doc -> {
			boolean first = true;
			for (VoucherData v : data) {
				if (!first) {
					doc.newPage();
				}
				first = false;
				float[] widths = new float[copies.size() * 2 - 1];
				for (int i = 0; i < widths.length; i++) {
					widths[i] = i % 2 == 0 ? 1f : 0.045f;
				}
				PdfPTable page = new PdfPTable(widths);
				page.setWidthPercentage(100);
				for (int i = 0; i < copies.size(); i++) {
					if (i > 0) {
						page.addCell(cutLine());
					}
					PdfPCell cell = new PdfPCell();
					cell.setPadding(0);
					cell.setBorderColor(PdfKit.tint(style.accent(), 0.5f));
					cell.setBorderWidth(0.8f);
					voucherCopy(cell, copies.get(i), lh, v, style);
					page.addCell(cell);
				}
				PdfKit.add(doc, page);
			}
		});
	}

	record VoucherData(BillView bill, BigDecimal arrears) {
	}

	record VoucherStyle(boolean showArrears, boolean showDiscount, boolean signature, BigDecimal lateFee, String bank,
			String note, boolean logo, Color accent) {
	}

	/** The gap between two copies: a dashed line to cut along. */
	private static PdfPCell cutLine() {
		PdfPCell gap = new PdfPCell();
		gap.setBorder(Rectangle.NO_BORDER);
		gap.setCellEvent((cell, position, canvases) -> {
			var cb = canvases[PdfPTable.LINECANVAS];
			cb.saveState();
			cb.setLineDash(3, 3, 0);
			cb.setLineWidth(0.5f);
			cb.setColorStroke(new Color(150, 150, 150));
			float x = (position.getLeft() + position.getRight()) / 2;
			cb.moveTo(x, position.getBottom());
			cb.lineTo(x, position.getTop());
			cb.stroke();
			cb.restoreState();
		});
		return gap;
	}

	private void voucherCopy(PdfPCell cell, String copy, Letterhead lh, VoucherData v, VoucherStyle s) {
		BillView b = v.bill();
		Color accent = s.accent();
		Font white = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);

		// Copy strip
		PdfPTable strip = new PdfPTable(2);
		strip.setWidthPercentage(100);
		PdfPCell left = new PdfPCell(new Phrase(copy, white));
		PdfPCell right = new PdfPCell(new Phrase("No. " + PdfKit.nz(b.billNo()), white));
		right.setHorizontalAlignment(Element.ALIGN_RIGHT);
		for (PdfPCell c : List.of(left, right)) {
			c.setBorder(Rectangle.NO_BORDER);
			c.setBackgroundColor(accent);
			c.setPadding(4);
			strip.addCell(c);
		}
		cell.addElement(strip);

		// School heading
		boolean logo = s.logo() && lh.logo() != null && lh.logo().length > 0;
		PdfPTable heading = new PdfPTable(logo ? new float[] { 1f, 4.2f } : new float[] { 1f });
		heading.setWidthPercentage(94);
		heading.setSpacingBefore(5);
		if (logo) {
			heading.addCell(PdfKit.imageCell(lh.logo(), 34));
		}
		PdfPCell names = new PdfPCell();
		names.setBorder(Rectangle.NO_BORDER);
		names.setVerticalAlignment(Element.ALIGN_MIDDLE);
		Paragraph name = new Paragraph(PdfKit.nz(lh.schoolName()), new Font(Font.HELVETICA, 12, Font.BOLD, accent));
		name.setAlignment(logo ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
		names.addElement(name);
		if (lh.address() != null) {
			Paragraph addr = new Paragraph(lh.address(), SMALL_MUTED);
			addr.setAlignment(logo ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
			names.addElement(addr);
		}
		heading.addCell(names);
		cell.addElement(heading);

		PdfPTable body = new PdfPTable(1);
		body.setWidthPercentage(94);
		body.setSpacingBefore(4);
		if (s.bank() != null && !s.bank().isBlank()) {
			PdfPCell bank = new PdfPCell(new Phrase(s.bank(), SMALL_BOLD));
			bank.setHorizontalAlignment(Element.ALIGN_CENTER);
			bank.setBackgroundColor(PdfKit.tint(accent, 0.08f));
			bank.setBorderColor(PdfKit.tint(accent, 0.3f));
			bank.setPadding(4);
			body.addCell(bank);
		}
		if (b.title() != null && !b.title().isBlank()) {
			PdfPCell title = new PdfPCell(new Phrase(b.title(), new Font(Font.HELVETICA, 9.5f, Font.BOLD, accent)));
			title.setBorder(Rectangle.NO_BORDER);
			title.setHorizontalAlignment(Element.ALIGN_CENTER);
			title.setPaddingTop(4);
			title.setPaddingBottom(2);
			body.addCell(title);
		}
		body.addCell(wrap(infoGrid(b, accent)));
		body.addCell(wrap(feeLines(b, v.arrears(), s)));

		BigDecimal payable = new BigDecimal(b.balance()).add(v.arrears());
		PdfPCell words = new PdfPCell(new Phrase(PdfKit.amountInWords(payable), WORDS));
		words.setBorder(Rectangle.NO_BORDER);
		words.setPaddingTop(3);
		body.addCell(words);
		if (s.note() != null && !s.note().isBlank()) {
			PdfPCell n = new PdfPCell(new Phrase(s.note(), SMALL_MUTED));
			n.setBorder(Rectangle.NO_BORDER);
			n.setPaddingTop(2);
			body.addCell(n);
		}

		PdfPTable signs = new PdfPTable(2);
		signs.setWidthPercentage(100);
		for (String who : List.of("Depositor", s.signature() ? "Accounts office" : "Bank stamp & signature")) {
			PdfPCell c = new PdfPCell();
			c.setBorder(Rectangle.NO_BORDER);
			c.setPaddingTop(34);
			c.addElement(new Paragraph(new Chunk(new org.openpdf.text.pdf.draw.LineSeparator(0.6f, 88f, PdfKit.MUTED,
					Element.ALIGN_CENTER, -2))));
			Paragraph p = new Paragraph(who, SMALL_MUTED);
			p.setAlignment(Element.ALIGN_CENTER);
			c.addElement(p);
			signs.addCell(c);
		}
		body.addCell(wrap(signs));
		cell.addElement(body);
		cell.addElement(new Paragraph(" ", new Font(Font.HELVETICA, 3)));
	}

	private static PdfPCell wrap(PdfPTable table) {
		PdfPCell c = new PdfPCell(table);
		c.setBorder(Rectangle.NO_BORDER);
		c.setPadding(0);
		c.setPaddingTop(3);
		return c;
	}

	private static PdfPTable infoGrid(BillView b, Color accent) {
		PdfPTable info = new PdfPTable(new float[] { 1.05f, 1.9f, 0.95f, 1.3f });
		info.setWidthPercentage(100);
		Color bg = PdfKit.tint(accent, 0.07f);
		info(info, bg, "Student", b.studentName(), 3);
		info(info, bg, "Father", b.fatherName(), 3);
		info(info, bg, "GR no.", b.grNo(), 1);
		info(info, bg, "Family", b.familyNo(), 1);
		info(info, bg, "Class", b.className() == null ? "" : b.className() + " - " + PdfKit.nz(b.sectionName()), 3);
		info(info, bg, "Issued", PdfKit.date(b.issueDate()), 1);
		info(info, bg, "Due date", PdfKit.date(b.dueDate()), 1);
		info(info, bg, "Valid until", PdfKit.date(b.validityDate()), 3);
		return info;
	}

	private static void info(PdfPTable table, Color bg, String label, String value, int span) {
		PdfPCell l = new PdfPCell(new Phrase(label, SMALL_LABEL));
		l.setBackgroundColor(bg);
		l.setBorderColor(PdfKit.BORDER);
		l.setPadding(3.5f);
		PdfPCell v = new PdfPCell(new Phrase(PdfKit.nz(value), "Due date".equals(label) ? SMALL_BOLD : SMALL));
		v.setBorderColor(PdfKit.BORDER);
		v.setPadding(3.5f);
		v.setColspan(span);
		table.addCell(l);
		table.addCell(v);
	}

	private static PdfPTable feeLines(BillView b, BigDecimal arrears, VoucherStyle s) {
		Color accent = s.accent();
		PdfPTable lines = new PdfPTable(new float[] { 3f, 1.4f });
		lines.setWidthPercentage(100);
		Font head = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
		for (String text : List.of("Fee", "Amount (Rs.)")) {
			PdfPCell c = new PdfPCell(new Phrase(text, head));
			c.setBackgroundColor(accent);
			c.setBorderColor(accent);
			c.setPadding(3);
			if (!"Fee".equals(text)) {
				c.setHorizontalAlignment(Element.ALIGN_RIGHT);
			}
			lines.addCell(c);
		}
		for (BillLineView l : b.lines()) {
			if (s.showDiscount() && new BigDecimal(l.discount()).signum() > 0) {
				amountRow(lines, l.description(), new BigDecimal(l.gross()), Row.PLAIN, accent);
				amountRow(lines, "   Less discount", new BigDecimal(l.discount()).negate(), Row.PLAIN, accent);
			}
			else {
				amountRow(lines, l.description(), new BigDecimal(l.net()), Row.PLAIN, accent);
			}
			if (new BigDecimal(l.adjusted()).signum() != 0) {
				amountRow(lines, "   Correction", new BigDecimal(l.adjusted()), Row.PLAIN, accent);
			}
		}
		BigDecimal paid = new BigDecimal(b.paid());
		if (paid.signum() > 0) {
			amountRow(lines, "Already paid", paid.negate(), Row.PLAIN, accent);
		}
		BigDecimal thisBill = new BigDecimal(b.balance());
		amountRow(lines, "This bill", thisBill, Row.SUBTOTAL, accent);
		if (s.showArrears() && arrears.signum() > 0) {
			amountRow(lines, "Arrears (earlier unpaid)", arrears, Row.PLAIN, accent);
		}
		BigDecimal payable = thisBill.add(arrears);
		amountRow(lines, "Payable by due date", payable, Row.TOTAL, accent);
		if (s.lateFee().signum() > 0) {
			amountRow(lines, "Payable after due date", payable.add(s.lateFee()), Row.SUBTOTAL, accent);
		}
		return lines;
	}

	private enum Row {
		PLAIN, SUBTOTAL, TOTAL
	}

	private static void amountRow(PdfPTable table, String label, BigDecimal amount, Row kind, Color accent) {
		Font font = switch (kind) {
			case TOTAL -> new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
			case SUBTOTAL -> SMALL_BOLD;
			case PLAIN -> SMALL;
		};
		Color bg = switch (kind) {
			case TOTAL -> accent;
			case SUBTOTAL -> PdfKit.tint(accent, 0.1f);
			case PLAIN -> null;
		};
		PdfPCell l = new PdfPCell(new Phrase(label, font));
		PdfPCell r = new PdfPCell(new Phrase(new java.text.DecimalFormat("#,##0.00").format(amount), font));
		r.setHorizontalAlignment(Element.ALIGN_RIGHT);
		for (PdfPCell c : List.of(l, r)) {
			c.setPadding(kind == Row.TOTAL ? 5 : 3.5f);
			c.setBorderColor(kind == Row.TOTAL ? accent : PdfKit.BORDER);
			if (bg != null) {
				c.setBackgroundColor(bg);
			}
			table.addCell(c);
		}
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
		String subject = p.receiptNo() + (p.studentName() != null ? " — " + p.studentName() : " — " + p.fatherName());
		var verify = verification.register(DocKind.FEE_RECEIPT, "payment", paymentId, "Fee receipt", subject, p.paymentDate());
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
				c.setBackgroundColor(PdfKit.tint(PdfKit.accent(), 0.1f));
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
			PdfKit.verificationQr(doc, verify.verifyUrl());
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
				c.setBackgroundColor(PdfKit.tint(PdfKit.accent(), 0.1f));
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
				values (?, ?, ?, ?, ?)""", com.tirvextech.sms.shared.tenant.TenantContext.requireSchoolId(), familyId,
				"Final reminder letter issued for " + Amounts.rupees(due), java.sql.Date.valueOf(payBy),
				com.tirvextech.sms.shared.security.CurrentActor.require().displayName());
		audit.record("FEE_FINAL_REMINDER", "family", familyId, null, Map.of("amount", due.toPlainString()));
		return pdf;
	}
}
