package com.selectcode.sms.shared.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Small toolkit for the school's printed documents: A4 pages with the letterhead, titles, text, label/value tables
 * and signature rows. English only (DECISIONS D-18), standard PDF fonts.
 */
public final class PdfKit {

	public static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);

	public static final Font HEADING = new Font(Font.HELVETICA, 12, Font.BOLD);

	public static final Font BODY = new Font(Font.HELVETICA, 11);

	public static final Font BODY_BOLD = new Font(Font.HELVETICA, 11, Font.BOLD);

	public static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(80, 80, 80));

	public static final Font SCHOOL_NAME = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(20, 40, 90));

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);

	private PdfKit() {
	}

	/** Renders an A4 document and returns the PDF bytes. */
	public static byte[] a4(Consumer<Document> content) {
		return render(PageSize.A4, 40, content);
	}

	public static byte[] render(Rectangle size, float margin, Consumer<Document> content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = new Document(size, margin, margin, margin, margin);
		PdfWriter.getInstance(document, out);
		document.open();
		content.accept(document);
		document.close();
		return out.toByteArray();
	}

	/** Logo at the left, school name, campus, address and contacts centred, then a rule. */
	public static void letterhead(Document doc, Letterhead lh) {
		PdfPTable table = new PdfPTable(new float[] { 1.2f, 6f, 1.2f });
		table.setWidthPercentage(100);
		table.addCell(imageCell(lh.logo(), 60));
		PdfPCell text = new PdfPCell();
		text.setBorder(Rectangle.NO_BORDER);
		text.setHorizontalAlignment(Element.ALIGN_CENTER);
		Paragraph name = new Paragraph(nz(lh.schoolName()), SCHOOL_NAME);
		name.setAlignment(Element.ALIGN_CENTER);
		text.addElement(name);
		addCentred(text, lh.campusName(), BODY);
		addCentred(text, lh.address(), SMALL);
		String contacts = join(" | ", lh.phones() == null ? null : "Phone: " + lh.phones(),
				lh.email() == null ? null : "Email: " + lh.email());
		addCentred(text, contacts, SMALL);
		table.addCell(text);
		table.addCell(empty());
		add(doc, table);
		Paragraph rule = new Paragraph(new Chunk(new org.openpdf.text.pdf.draw.LineSeparator(1f, 100f, Color.GRAY,
				Element.ALIGN_CENTER, -2)));
		rule.setSpacingAfter(12);
		add(doc, rule);
	}

	public static void title(Document doc, String text) {
		Paragraph p = new Paragraph(text, TITLE);
		p.setAlignment(Element.ALIGN_CENTER);
		p.setSpacingBefore(6);
		p.setSpacingAfter(14);
		add(doc, p);
	}

	public static void paragraph(Document doc, String text) {
		Paragraph p = new Paragraph(text, BODY);
		p.setAlignment(Element.ALIGN_JUSTIFIED);
		p.setLeading(18);
		p.setSpacingAfter(10);
		add(doc, p);
	}

	public static void line(Document doc, String left, String right) {
		PdfPTable table = new PdfPTable(2);
		table.setWidthPercentage(100);
		PdfPCell l = new PdfPCell(new Phrase(nz(left), BODY));
		l.setBorder(Rectangle.NO_BORDER);
		PdfPCell r = new PdfPCell(new Phrase(nz(right), BODY));
		r.setBorder(Rectangle.NO_BORDER);
		r.setHorizontalAlignment(Element.ALIGN_RIGHT);
		table.addCell(l);
		table.addCell(r);
		table.setSpacingAfter(10);
		add(doc, table);
	}

	/** Two-column label/value table, e.g. a bio-data sheet. */
	public static void details(Document doc, List<String[]> rows) {
		PdfPTable table = new PdfPTable(new float[] { 2.2f, 5f });
		table.setWidthPercentage(100);
		for (String[] row : rows) {
			PdfPCell label = new PdfPCell(new Phrase(row[0], BODY_BOLD));
			label.setPadding(5);
			label.setBackgroundColor(new Color(243, 244, 246));
			PdfPCell value = new PdfPCell(new Phrase(nz(row[1]), BODY));
			value.setPadding(5);
			table.addCell(label);
			table.addCell(value);
		}
		table.setSpacingAfter(12);
		add(doc, table);
	}

	/** Signature boxes at the bottom: image (if uploaded), line, name and role. Unknown roles print a blank line. */
	public static void signatures(Document doc, Letterhead lh, List<String> roles, boolean includeStamp) {
		int columns = roles.size() + (includeStamp ? 1 : 0);
		if (columns == 0) {
			return;
		}
		PdfPTable table = new PdfPTable(columns);
		table.setWidthPercentage(100);
		table.setSpacingBefore(40);
		if (includeStamp) {
			table.addCell(imageCell(lh.stamp(), 70));
		}
		for (String role : roles) {
			var sig = lh.signature(role);
			PdfPCell cell = new PdfPCell();
			cell.setBorder(Rectangle.NO_BORDER);
			cell.setHorizontalAlignment(Element.ALIGN_CENTER);
			if (sig.isPresent() && sig.get().image() != null) {
				Image image = image(sig.get().image());
				if (image != null) {
					image.scaleToFit(110, 40);
					image.setAlignment(Element.ALIGN_CENTER);
					cell.addElement(image);
				}
			}
			addCentred(cell, "______________________", BODY);
			addCentred(cell, sig.map(Letterhead.SignatureImage::name).orElse(""), BODY_BOLD);
			addCentred(cell, sig.map(s -> s.title() == null || s.title().isBlank() ? s.role() : s.title()).orElse(role),
					SMALL);
			table.addCell(cell);
		}
		add(doc, table);
	}

	public static String date(LocalDate date) {
		return date == null ? "—" : DATE.format(date);
	}

	/** "15 March 2014" style, for dates of birth written in words on certificates. */
	public static String dateInWords(LocalDate date) {
		if (date == null) {
			return "—";
		}
		return ordinalWords(date.getDayOfMonth()) + " "
				+ date.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " "
				+ numberWords(date.getYear());
	}

	static String ordinalWords(int day) {
		String[] ordinals = { "", "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth",
				"Tenth", "Eleventh", "Twelfth", "Thirteenth", "Fourteenth", "Fifteenth", "Sixteenth", "Seventeenth",
				"Eighteenth", "Nineteenth", "Twentieth", "Twenty-first", "Twenty-second", "Twenty-third",
				"Twenty-fourth", "Twenty-fifth", "Twenty-sixth", "Twenty-seventh", "Twenty-eighth", "Twenty-ninth",
				"Thirtieth", "Thirty-first" };
		return ordinals[day];
	}

	/** e.g. 125000.50 → "Rupees One Lakh Twenty-Five Thousand and Fifty Paisa Only" (South Asian grouping). */
	public static String amountInWords(java.math.BigDecimal amount) {
		java.math.BigDecimal a = amount.setScale(2, java.math.RoundingMode.HALF_UP).abs();
		long rupees = a.longValue();
		int paisa = a.remainder(java.math.BigDecimal.ONE).movePointRight(2).intValue();
		String words = rupees == 0 ? "Zero" : groupWords(rupees);
		return "Rupees " + words + (paisa > 0 ? " and " + numberWords(paisa) + " Paisa" : "") + " Only";
	}

	private static String groupWords(long n) {
		StringBuilder sb = new StringBuilder();
		long[] units = { 10_000_000L, 100_000L, 1_000L };
		String[] names = { "Crore", "Lakh", "Thousand" };
		for (int i = 0; i < units.length; i++) {
			if (n >= units[i]) {
				long part = n / units[i];
				sb.append(part >= 100 && i == 0 ? groupWords(part) : numberWords((int) part)).append(' ').append(names[i]).append(' ');
				n %= units[i];
			}
		}
		if (n > 0) {
			sb.append(numberWords((int) n));
		}
		return sb.toString().trim();
	}

	static String numberWords(int n) {
		String[] ones = { "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven",
				"Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };
		String[] tens = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety" };
		if (n < 20) {
			return ones[n];
		}
		if (n < 100) {
			return tens[n / 10] + (n % 10 == 0 ? "" : "-" + ones[n % 10]);
		}
		if (n < 1000) {
			return ones[n / 100] + " Hundred" + (n % 100 == 0 ? "" : " and " + numberWords(n % 100));
		}
		return numberWords(n / 1000) + " Thousand" + (n % 1000 == 0 ? "" : (n % 1000 < 100 ? " and " : " ")
				+ numberWords(n % 1000));
	}

	static Image image(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		try {
			return Image.getInstance(bytes);
		}
		catch (Exception ex) {
			return null; // unsupported image (e.g. WebP): print without it
		}
	}

	public static PdfPCell imageCell(byte[] bytes, float maxSize) {
		PdfPCell cell = new PdfPCell();
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
		cell.setHorizontalAlignment(Element.ALIGN_CENTER);
		Image image = image(bytes);
		if (image != null) {
			image.scaleToFit(maxSize, maxSize);
			cell.setImage(image);
		}
		return cell;
	}

	public static PdfPCell empty() {
		PdfPCell cell = new PdfPCell();
		cell.setBorder(Rectangle.NO_BORDER);
		return cell;
	}

	public static void add(Document doc, Element element) {
		try {
			doc.add(element);
		}
		catch (org.openpdf.text.DocumentException ex) {
			throw new IllegalStateException("Could not build PDF", ex);
		}
	}

	private static void addCentred(PdfPCell cell, String text, Font font) {
		if (text == null || text.isBlank()) {
			return;
		}
		Paragraph p = new Paragraph(text, font);
		p.setAlignment(Element.ALIGN_CENTER);
		cell.addElement(p);
	}

	public static String nz(String value) {
		return value == null ? "" : value;
	}

	private static String join(String separator, String... parts) {
		return java.util.Arrays.stream(parts)
			.filter(p -> p != null && !p.isBlank())
			.reduce((a, b) -> a + separator + b)
			.orElse(null);
	}
}
