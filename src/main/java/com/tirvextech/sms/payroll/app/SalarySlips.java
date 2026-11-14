package com.selectcode.sms.payroll.app;

import com.selectcode.sms.payroll.app.PayrollService.LineView;
import com.selectcode.sms.payroll.app.PayrollService.SheetView;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.web.ApiException;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Salary slips (DECISIONS D-04): one A5 page per staff member with the deduction breakdown (QA-PAY-001). */
@Service
public class SalarySlips {

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	private final PayrollService payroll;

	private final LetterheadSource letterhead;

	SalarySlips(PayrollService payroll, LetterheadSource letterhead) {
		this.payroll = payroll;
		this.letterhead = letterhead;
	}

	@Transactional(readOnly = true)
	public byte[] slips(long sheetId, Long lineId) {
		SheetView sheet = payroll.get(sheetId);
		if ("DRAFT".equals(sheet.status()) || "REVERSED".equals(sheet.status())) {
			throw ApiException.businessRule("NOT_APPROVED", "Slips are printed from an approved sheet");
		}
		List<LineView> lines = lineId == null ? sheet.lines()
				: sheet.lines().stream().filter(l -> l.id() == lineId).toList();
		if (lines.isEmpty()) {
			throw ApiException.notFound("Salary line");
		}
		Letterhead lh = letterhead.current();
		String month = YearMonth.parse(sheet.month()).format(MONTH);
		return PdfKit.render(PageSize.A5, 28, doc -> {
			boolean first = true;
			for (LineView l : lines) {
				if (!first) {
					doc.newPage();
				}
				first = false;
				PdfKit.letterhead(doc, lh);
				PdfKit.title(doc, "SALARY SLIP - " + month.toUpperCase(Locale.ENGLISH));
				List<String[]> who = new ArrayList<>();
				who.add(new String[] { "Employee no.", l.employeeNo() });
				who.add(new String[] { "Name", l.staffName() });
				who.add(new String[] { "Designation", l.designation() });
				who.add(new String[] { "Working days", String.valueOf(l.workingDays()) });
				who.add(new String[] { "Attendance", "Absent " + l.absentDays() + ", half days " + l.halfDays() + ", late "
						+ l.lateDays() + ", leave " + l.leaveDays() });
				PdfKit.details(doc, who);
				PdfPTable t = new PdfPTable(new float[] { 4f, 2f });
				t.setWidthPercentage(100);
				row(t, "Basic salary", l.basic(), false);
				if (l.lectures() > 0 && l.perLectureRate() != null) {
					row(t, "Lectures: " + l.lectures() + " × " + MoneyInput.rupees(new BigDecimal(l.perLectureRate())),
							MoneyInput.text(new BigDecimal(l.perLectureRate()).multiply(BigDecimal.valueOf(l.lectures()))),
							false);
				}
				if (new BigDecimal(l.allowance()).signum() > 0) {
					row(t, "Allowance", l.allowance(), false);
				}
				if (new BigDecimal(l.attendanceDeduction()).signum() > 0) {
					row(t, "Less attendance deduction (" + l.deductionDays() + " days × "
							+ MoneyInput.rupees(new BigDecimal(String.valueOf(l.breakdown().get("perDay")))) + ")",
							"-" + l.attendanceDeduction(), false);
				}
				row(t, "Earned", l.earned(), true);
				if (new BigDecimal(l.tax()).signum() > 0) {
					row(t, "Less income tax", "-" + l.tax(), false);
				}
				if (new BigDecimal(l.otherDeduction()).signum() > 0) {
					row(t, "Less " + (l.otherNote() == null ? "other deduction" : l.otherNote()), "-" + l.otherDeduction(),
							false);
				}
				row(t, "Net salary", l.net(), true);
				t.setSpacingAfter(8);
				PdfKit.add(doc, t);
				PdfKit.paragraph(doc, PdfKit.amountInWords(new BigDecimal(l.net())));
				if (l.breakdown().get("lateDaysAsAbsence") != null
						&& !"0".equals(String.valueOf(l.breakdown().get("lateDaysAsAbsence")))) {
					PdfKit.paragraph(doc, "Every " + l.breakdown().get("lateDaysPerAbsence")
							+ " late days count as one absence.");
				}
				PdfKit.line(doc, "PAID".equals(sheet.status()) ? "Paid on " + PdfKit.date(sheet.paymentDate())
						+ " from " + sheet.paymentAccountName() : "Approved, not yet paid", "");
				PdfKit.signatures(doc, lh, List.of("Accountant", "Principal"), false);
			}
		});
	}

	private static void row(PdfPTable t, String label, String amount, boolean bold) {
		PdfPCell l = new PdfPCell(new Phrase(label, bold ? PdfKit.BODY_BOLD : PdfKit.BODY));
		l.setPadding(4);
		PdfPCell a = new PdfPCell(new Phrase(new java.text.DecimalFormat("#,##0.00").format(new BigDecimal(amount)),
				bold ? PdfKit.BODY_BOLD : PdfKit.BODY));
		a.setPadding(4);
		a.setHorizontalAlignment(Element.ALIGN_RIGHT);
		if (bold) {
			l.setBackgroundColor(new Color(243, 244, 246));
			a.setBackgroundColor(new Color(243, 244, 246));
		}
		t.addCell(l);
		t.addCell(a);
	}
}
