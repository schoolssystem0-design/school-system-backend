package com.selectcode.sms.reports.app;

import com.selectcode.sms.reports.app.ReportDoc.Column;
import com.selectcode.sms.reports.app.ReportDoc.Kind;
import com.selectcode.sms.reports.app.ReportDoc.Section;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.PdfKit;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;

/** Draws a {@link ReportDoc} as PDF (FR 15.6) or as Excel with real numeric cells (QA-RPT-004). */
final class ReportRenderer {

	private static final org.openpdf.text.Font CELL = new org.openpdf.text.Font(org.openpdf.text.Font.HELVETICA, 8.5f);

	private static final org.openpdf.text.Font CELL_BOLD = new org.openpdf.text.Font(org.openpdf.text.Font.HELVETICA, 8.5f,
			org.openpdf.text.Font.BOLD);

	/** "Rs. 1,234.50", "1,234", "-12.5", "12.5%" on screens exported as they are; codes like 00123 stay text. */
	private static final Pattern NUMERIC = Pattern.compile("^(Rs\\.?\\s*)?(-?)((?:0|[1-9]\\d{0,2}(?:,\\d{3})+|[1-9]\\d*)(?:\\.\\d+)?)(%?)$");

	private ReportRenderer() {
	}

	// -------------------------------------------------------------------- PDF

	static byte[] pdf(ReportDoc doc, Letterhead letterhead) {
		int widest = doc.sections().stream().mapToInt(s -> s.columns().size()).max().orElse(1);
		return PdfKit.render(widest > 7 ? PageSize.A4.rotate() : PageSize.A4, 30, d -> {
			PdfKit.letterhead(d, letterhead);
			PdfKit.title(d, doc.title());
			for (String line : doc.heading()) {
				Paragraph p = new Paragraph(line, PdfKit.BODY);
				p.setAlignment(Element.ALIGN_CENTER);
				PdfKit.add(d, p);
			}
			doc.sections().forEach(s -> section(d, s));
		});
	}

	private static void section(Document d, Section s) {
		Paragraph title = new Paragraph(s.title(), PdfKit.HEADING);
		title.setSpacingBefore(12);
		title.setSpacingAfter(4);
		PdfKit.add(d, title);
		if (s.note() != null) {
			Paragraph note = new Paragraph(s.note(), PdfKit.SMALL);
			note.setSpacingAfter(4);
			PdfKit.add(d, note);
		}
		if (s.rows().isEmpty()) {
			PdfKit.add(d, new Paragraph("None", CELL));
			return;
		}
		PdfPTable t = new PdfPTable(s.columns().size());
		t.setWidthPercentage(100);
		t.setHeaderRows(1);
		for (Column c : s.columns()) {
			t.addCell(cell(c.name(), CELL_BOLD, c.kind(), new Color(243, 244, 246)));
		}
		for (List<String> row : s.rows()) {
			for (int i = 0; i < s.columns().size(); i++) {
				String v = i < row.size() ? row.get(i) : null;
				t.addCell(cell(v == null ? "" : v, CELL, s.columns().get(i).kind(), null));
			}
		}
		PdfKit.add(d, t);
	}

	private static PdfPCell cell(String text, org.openpdf.text.Font font, Kind kind, Color background) {
		PdfPCell c = new PdfPCell(new Phrase(text, font));
		c.setPadding(3.5f);
		if (kind != Kind.TEXT && kind != Kind.AUTO) {
			c.setHorizontalAlignment(Element.ALIGN_RIGHT);
		}
		if (background != null) {
			c.setBackgroundColor(background);
		}
		return c;
	}

	// ------------------------------------------------------------------ Excel

	/** One sheet with every section under the heading; {@code sheetPerSection} gives each section its own sheet. */
	static byte[] xlsx(ReportDoc doc, boolean sheetPerSection) {
		try (SXSSFWorkbook book = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Styles styles = new Styles(book);
			Set<String> used = new HashSet<>();
			if (sheetPerSection) {
				for (Section s : doc.sections()) {
					int[] next = { 0 };
					SXSSFSheet sheet = newSheet(book, s.title(), used);
					writeHeading(sheet, doc, styles, next);
					writeSection(sheet, s, styles, next);
				}
			}
			else {
				int[] next = { 0 };
				SXSSFSheet sheet = newSheet(book, doc.title(), used);
				writeHeading(sheet, doc, styles, next);
				doc.sections().forEach(s -> writeSection(sheet, s, styles, next));
			}
			book.write(out);
			book.dispose();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static SXSSFSheet newSheet(SXSSFWorkbook book, String title, Set<String> used) {
		String base = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(title);
		base = base.length() > 28 ? base.substring(0, 28) : base;
		String name = base;
		for (int i = 2; !used.add(name.toLowerCase()); i++) {
			name = base + " " + i;
		}
		SXSSFSheet sheet = book.createSheet(name);
		sheet.setDefaultColumnWidth(16);
		return sheet;
	}

	private static void writeHeading(Sheet sheet, ReportDoc doc, Styles styles, int[] next) {
		text(sheet.createRow(next[0]++), 0, doc.title(), styles.title);
		for (String line : doc.heading()) {
			text(sheet.createRow(next[0]++), 0, line, null);
		}
		next[0]++;
	}

	private static void writeSection(Sheet sheet, Section s, Styles styles, int[] next) {
		text(sheet.createRow(next[0]++), 0, s.title(), styles.title);
		if (s.note() != null) {
			text(sheet.createRow(next[0]++), 0, s.note(), null);
		}
		Row head = sheet.createRow(next[0]++);
		for (int i = 0; i < s.columns().size(); i++) {
			text(head, i, s.columns().get(i).name(), styles.bold);
		}
		for (List<String> values : s.rows()) {
			Row row = sheet.createRow(next[0]++);
			for (int i = 0; i < s.columns().size() && i < values.size(); i++) {
				write(row, i, values.get(i), s.columns().get(i).kind(), styles);
			}
		}
		next[0]++;
	}

	private static void write(Row row, int column, String value, Kind kind, Styles styles) {
		if (value == null || value.isBlank()) {
			return;
		}
		Parsed parsed = kind == Kind.TEXT ? null : parse(value);
		if (parsed == null) {
			text(row, column, value, null);
			return;
		}
		var cell = row.createCell(column);
		// Excel stores numbers as doubles; the text sent by the API stays exact on screen and in the PDF
		if (parsed.percent() || kind == Kind.PERCENT) {
			cell.setCellValue(parsed.value().movePointLeft(2).doubleValue());
			cell.setCellStyle(styles.percent);
		}
		else if (parsed.money() || kind == Kind.MONEY) {
			cell.setCellValue(parsed.value().doubleValue());
			cell.setCellStyle(styles.money);
		}
		else {
			cell.setCellValue(parsed.value().doubleValue());
			cell.setCellStyle(styles.number);
		}
	}

	record Parsed(BigDecimal value, boolean money, boolean percent) {
	}

	/** The number in a displayed value, or null if it should stay text. */
	static Parsed parse(String value) {
		Matcher m = NUMERIC.matcher(value.trim());
		if (!m.matches()) {
			return null;
		}
		BigDecimal number = new BigDecimal(m.group(3).replace(",", ""));
		return new Parsed("-".equals(m.group(2)) ? number.negate() : number, m.group(1) != null, !m.group(4).isEmpty());
	}

	private static void text(Row row, int column, String value, CellStyle style) {
		var cell = row.createCell(column);
		// Always a text cell: a value starting with = can never run as a formula
		cell.setCellValue(value);
		if (style != null) {
			cell.setCellStyle(style);
		}
	}

	private static final class Styles {

		final CellStyle title;

		final CellStyle bold;

		final CellStyle money;

		final CellStyle number;

		final CellStyle percent;

		Styles(SXSSFWorkbook book) {
			Font boldFont = book.createFont();
			boldFont.setBold(true);
			title = book.createCellStyle();
			title.setFont(boldFont);
			bold = book.createCellStyle();
			bold.setFont(boldFont);
			var format = book.createDataFormat();
			money = book.createCellStyle();
			money.setDataFormat(format.getFormat("#,##0.00"));
			number = book.createCellStyle();
			number.setDataFormat(format.getFormat("#,##0.##"));
			percent = book.createCellStyle();
			percent.setDataFormat(format.getFormat("0.0#%"));
		}
	}

	static List<String> row(Object... values) {
		List<String> out = new ArrayList<>(values.length);
		for (Object v : values) {
			out.add(v == null ? null : v.toString());
		}
		return out;
	}
}
