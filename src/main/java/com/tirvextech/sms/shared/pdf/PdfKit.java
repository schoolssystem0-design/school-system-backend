package com.tirvextech.sms.shared.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Small toolkit for the school's printed documents: A4 pages with the letterhead, titles, text, label/value tables,
 * form fields and signature rows. English only (DECISIONS D-18), standard PDF fonts. Documents take the school's theme
 * colour, footer line and watermark from {@link Letterhead#style()}.
 */
public final class PdfKit {

	public static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);

	public static final Font HEADING = new Font(Font.HELVETICA, 12, Font.BOLD);

	public static final Font BODY = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, new Color(30, 32, 40));

	public static final Font BODY_BOLD = new Font(Font.HELVETICA, 10.5f, Font.BOLD, new Color(30, 32, 40));

	public static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(90, 94, 105));

	public static final Font SCHOOL_NAME = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(20, 40, 90));

	/** Label text on forms and detail tables. */
	public static final Font LABEL = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(70, 75, 88));

	/** Light grey for table borders. */
	public static final Color BORDER = new Color(212, 216, 224);

	public static final Color MUTED = new Color(110, 115, 128);

	/** The theme colours a school can choose for its documents (the setting's options, in this order). */
	public static final Map<String, Color> THEMES = themes();

	private static Map<String, Color> themes() {
		Map<String, Color> m = new LinkedHashMap<>();
		m.put("Navy blue", new Color(27, 50, 100));
		m.put("Royal blue", new Color(29, 78, 216));
		m.put("Green", new Color(21, 115, 61));
		m.put("Maroon", new Color(128, 28, 40));
		m.put("Teal", new Color(15, 110, 110));
		m.put("Purple", new Color(91, 33, 182));
		m.put("Black", new Color(33, 35, 46));
		return java.util.Collections.unmodifiableMap(m);
	}

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);

	/** The document being rendered on this thread: its writer and, once the letterhead is printed, its style. */
	private static final ThreadLocal<Page> CURRENT = new ThreadLocal<>();

	private static final class Page {

		final PdfWriter writer;

		Letterhead letterhead;

		Page(PdfWriter writer) {
			this.writer = writer;
		}
	}

	private PdfKit() {
	}

	/** A theme colour by name ("Navy blue" when unknown). */
	public static Color colour(String name) {
		Color c = THEMES.get(name == null ? "" : name);
		return c != null ? c : new Color(27, 50, 100);
	}

	/** {@code colour} mixed with white: 0 = white, 1 = the colour. */
	public static Color tint(Color colour, float amount) {
		return new Color(mix(colour.getRed(), amount), mix(colour.getGreen(), amount), mix(colour.getBlue(), amount));
	}

	private static int mix(int c, float amount) {
		return Math.round(255 - (255 - c) * amount);
	}

	/** The theme colour of the document being rendered (the default before the letterhead is printed). */
	public static Color accent() {
		Page page = CURRENT.get();
		return page == null || page.letterhead == null ? colour(null) : page.letterhead.style().accent();
	}

	/** Renders an A4 document and returns the PDF bytes. */
	public static byte[] a4(Consumer<Document> content) {
		return render(PageSize.A4, 40, content);
	}

	public static byte[] render(Rectangle size, float margin, Consumer<Document> content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = new Document(size, margin, margin, margin, margin + 6);
		PdfWriter writer = PdfWriter.getInstance(document, out);
		Page previous = CURRENT.get();
		CURRENT.set(new Page(writer));
		try {
			document.open();
			content.accept(document);
			document.close();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
		return out.toByteArray();
	}

	/**
	 * Logo at the left, the school name in the theme colour, campus, address and contacts centred, then a double rule.
	 * Also turns on the page footer (footer line, page numbers) and the watermark for the rest of the document.
	 */
	public static void letterhead(Document doc, Letterhead lh) {
		Page page = CURRENT.get();
		if (page != null && page.letterhead == null) {
			page.letterhead = lh;
			page.writer.setPageEvent(new Decorations(lh));
		}
		Color accent = lh.style().accent();
		boolean logo = image(lh.logo()) != null;
		PdfPTable table = new PdfPTable(logo ? new float[] { 1.25f, 6f, 1.25f } : new float[] { 1f });
		table.setWidthPercentage(100);
		if (logo) {
			table.addCell(imageCell(lh.logo(), 64));
		}
		PdfPCell text = new PdfPCell();
		text.setBorder(Rectangle.NO_BORDER);
		text.setHorizontalAlignment(Element.ALIGN_CENTER);
		text.setVerticalAlignment(Element.ALIGN_MIDDLE);
		Paragraph name = new Paragraph(nz(lh.schoolName()), new Font(Font.HELVETICA, 19, Font.BOLD, accent));
		name.setAlignment(Element.ALIGN_CENTER);
		text.addElement(name);
		addCentred(text, lh.campusName(), new Font(Font.HELVETICA, 10.5f, Font.BOLD, new Color(60, 64, 75)));
		addCentred(text, lh.address(), SMALL);
		String contacts = join("   |   ", lh.phones() == null ? null : "Phone: " + lh.phones(),
				lh.email() == null ? null : "Email: " + lh.email());
		addCentred(text, contacts, SMALL);
		table.addCell(text);
		if (logo) {
			table.addCell(empty());
		}
		add(doc, table);
		rule(doc, accent, 2.2f, 4, 1.5f);
		rule(doc, tint(accent, 0.35f), 0.7f, 0, 12);
	}

	private static void rule(Document doc, Color colour, float height, float before, float after) {
		PdfPTable t = new PdfPTable(1);
		t.setWidthPercentage(100);
		PdfPCell c = new PdfPCell();
		c.setBorder(Rectangle.NO_BORDER);
		c.setBackgroundColor(colour);
		c.setFixedHeight(height);
		t.addCell(c);
		t.setSpacingBefore(before);
		t.setSpacingAfter(after);
		add(doc, t);
	}

	/** The document title on a tinted band with a bar in the theme colour. */
	public static void title(Document doc, String text) {
		Color accent = accent();
		PdfPTable band = new PdfPTable(new float[] { 0.8f, 99.2f });
		band.setWidthPercentage(100);
		PdfPCell bar = new PdfPCell();
		bar.setBorder(Rectangle.NO_BORDER);
		bar.setBackgroundColor(accent);
		band.addCell(bar);
		PdfPCell cell = new PdfPCell(new Phrase(nz(text).toUpperCase(Locale.ENGLISH),
				new Font(Font.HELVETICA, 13.5f, Font.BOLD, accent)));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setBackgroundColor(tint(accent, 0.1f));
		cell.setHorizontalAlignment(Element.ALIGN_CENTER);
		cell.setPaddingTop(6);
		cell.setPaddingBottom(8);
		band.addCell(cell);
		band.setSpacingBefore(2);
		band.setSpacingAfter(14);
		add(doc, band);
	}

	/** A section heading inside a document, e.g. "Student information". */
	public static void section(Document doc, String text) {
		Color accent = accent();
		PdfPTable t = new PdfPTable(1);
		t.setWidthPercentage(100);
		PdfPCell c = new PdfPCell(new Phrase(nz(text), new Font(Font.HELVETICA, 10.5f, Font.BOLD, accent)));
		c.setBorder(Rectangle.BOTTOM);
		c.setBorderColorBottom(tint(accent, 0.45f));
		c.setBorderWidthBottom(1f);
		c.setPaddingLeft(0);
		c.setPaddingBottom(4);
		t.addCell(c);
		t.setSpacingBefore(8);
		t.setSpacingAfter(5);
		add(doc, t);
	}

	public static void paragraph(Document doc, String text) {
		Paragraph p = new Paragraph(text, BODY);
		p.setAlignment(Element.ALIGN_JUSTIFIED);
		p.setLeading(16);
		p.setSpacingAfter(9);
		add(doc, p);
	}

	/** Text in a light box with a small heading, e.g. terms and conditions. */
	public static void box(Document doc, String heading, String text) {
		PdfPTable t = new PdfPTable(1);
		t.setWidthPercentage(100);
		PdfPCell c = new PdfPCell();
		c.setBorderColor(BORDER);
		c.setBackgroundColor(new Color(250, 250, 252));
		c.setPadding(8);
		if (heading != null) {
			c.addElement(new Paragraph(heading, new Font(Font.HELVETICA, 9.5f, Font.BOLD, accent())));
		}
		Paragraph p = new Paragraph(nz(text), new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(50, 54, 64)));
		p.setLeading(12.5f);
		p.setAlignment(Element.ALIGN_JUSTIFIED);
		c.addElement(p);
		t.addCell(c);
		t.setSpacingAfter(10);
		add(doc, t);
	}

	public static void line(Document doc, String left, String right) {
		PdfPTable table = new PdfPTable(2);
		table.setWidthPercentage(100);
		PdfPCell l = new PdfPCell(new Phrase(nz(left), BODY_BOLD));
		l.setBorder(Rectangle.NO_BORDER);
		l.setPaddingLeft(0);
		PdfPCell r = new PdfPCell(new Phrase(nz(right), BODY));
		r.setBorder(Rectangle.NO_BORDER);
		r.setHorizontalAlignment(Element.ALIGN_RIGHT);
		r.setPaddingRight(0);
		table.addCell(l);
		table.addCell(r);
		table.setSpacingAfter(10);
		add(doc, table);
	}

	/** Two-column label/value table, e.g. a bio-data sheet. */
	public static void details(Document doc, List<String[]> rows) {
		PdfPTable table = new PdfPTable(new float[] { 2.2f, 5f });
		table.setWidthPercentage(100);
		Color labelBg = tint(accent(), 0.07f);
		for (String[] row : rows) {
			PdfPCell label = new PdfPCell(new Phrase(row[0], LABEL));
			label.setPadding(6);
			label.setBorderColor(BORDER);
			label.setBackgroundColor(labelBg);
			PdfPCell value = new PdfPCell(new Phrase(nz(row[1]), BODY));
			value.setPadding(6);
			value.setBorderColor(BORDER);
			table.addCell(label);
			table.addCell(value);
		}
		table.setSpacingAfter(12);
		add(doc, table);
	}

	/**
	 * Form fields in a grid of {@code pairs} label/value pairs per row, with room to write by hand when a value is
	 * empty. A field whose label ends with "*" takes the whole row.
	 */
	public static void fields(Document doc, List<String[]> fields, int pairs) {
		float[] widths = new float[pairs * 2];
		for (int i = 0; i < pairs; i++) {
			widths[i * 2] = 1.35f;
			widths[i * 2 + 1] = 2.4f;
		}
		PdfPTable table = new PdfPTable(widths);
		table.setWidthPercentage(100);
		Color labelBg = tint(accent(), 0.07f);
		int used = 0;
		for (String[] f : fields) {
			boolean wide = f[0].endsWith("*");
			if (wide && used > 0) {
				fillRow(table, pairs - used);
				used = 0;
			}
			PdfPCell label = new PdfPCell(new Phrase(wide ? f[0].substring(0, f[0].length() - 1) : f[0], LABEL));
			label.setBorderColor(BORDER);
			label.setBackgroundColor(labelBg);
			label.setPadding(5);
			label.setVerticalAlignment(Element.ALIGN_MIDDLE);
			PdfPCell value = new PdfPCell(new Phrase(nz(f[1]), BODY));
			value.setBorderColor(BORDER);
			value.setPadding(5);
			value.setMinimumHeight(22);
			value.setVerticalAlignment(Element.ALIGN_MIDDLE);
			if (wide) {
				value.setColspan(pairs * 2 - 1);
			}
			table.addCell(label);
			table.addCell(value);
			used = wide ? 0 : (used + 1) % pairs;
		}
		if (used > 0) {
			fillRow(table, pairs - used);
		}
		table.setSpacingAfter(6);
		add(doc, table);
	}

	private static void fillRow(PdfPTable table, int missingPairs) {
		PdfPCell blank = new PdfPCell();
		blank.setBorderColor(BORDER);
		blank.setColspan(missingPairs * 2);
		table.addCell(blank);
	}

	/** Tick boxes, e.g. the documents attached to an admission form. */
	public static void checklist(Document doc, List<String> items, int columns) {
		if (items.isEmpty()) {
			return;
		}
		PdfPTable table = new PdfPTable(columns);
		table.setWidthPercentage(100);
		for (String item : items) {
			PdfPTable row = new PdfPTable(new float[] { 0.6f, 9f });
			row.setWidthPercentage(100);
			PdfPCell box = new PdfPCell();
			box.setFixedHeight(9);
			box.setBorderColor(MUTED);
			box.setBorderWidth(0.8f);
			row.addCell(box);
			PdfPCell text = new PdfPCell(new Phrase(item, new Font(Font.HELVETICA, 9.5f, Font.NORMAL, new Color(40, 44, 54))));
			text.setBorder(Rectangle.NO_BORDER);
			text.setPaddingTop(0);
			text.setPaddingLeft(5);
			row.addCell(text);
			PdfPCell holder = new PdfPCell(row);
			holder.setBorder(Rectangle.NO_BORDER);
			holder.setPadding(3);
			table.addCell(holder);
		}
		int remainder = items.size() % columns;
		for (int i = remainder; remainder != 0 && i < columns; i++) {
			table.addCell(empty());
		}
		table.setSpacingAfter(6);
		add(doc, table);
	}

	/** Signature boxes at the bottom: image (if uploaded), line, name and role. Unknown roles print a blank line. */
	public static void signatures(Document doc, Letterhead lh, List<String> roles, boolean includeStamp) {
		int columns = roles.size() + (includeStamp ? 1 : 0);
		if (columns == 0) {
			return;
		}
		PdfPTable table = new PdfPTable(columns);
		// One signer: a short line at the right, not across the page
		table.setWidthPercentage(columns == 1 ? 38 : 100);
		table.setHorizontalAlignment(columns == 1 ? Element.ALIGN_RIGHT : Element.ALIGN_CENTER);
		table.setSpacingBefore(36);
		table.setKeepTogether(true);
		if (includeStamp) {
			table.addCell(imageCell(lh.stamp(), 70));
		}
		for (String role : roles) {
			var sig = lh.signature(role);
			PdfPCell cell = new PdfPCell();
			cell.setBorder(Rectangle.NO_BORDER);
			cell.setHorizontalAlignment(Element.ALIGN_CENTER);
			cell.setVerticalAlignment(Element.ALIGN_BOTTOM);
			if (sig.isPresent() && sig.get().image() != null) {
				Image image = image(sig.get().image());
				if (image != null) {
					image.scaleToFit(110, 40);
					image.setAlignment(Element.ALIGN_CENTER);
					cell.addElement(image);
				}
			}
			cell.addElement(new Paragraph(new Chunk(new org.openpdf.text.pdf.draw.LineSeparator(0.8f, 80f, MUTED,
					Element.ALIGN_CENTER, -2))));
			addCentred(cell, sig.map(Letterhead.SignatureImage::name).orElse(""), BODY_BOLD);
			addCentred(cell, sig.map(s -> s.title() == null || s.title().isBlank() ? s.role() : s.title()).orElse(role),
					SMALL);
			table.addCell(cell);
		}
		add(doc, table);
	}

	/** Footer (school name or footer line, page number) and the faint logo watermark, on every page. */
	private static final class Decorations extends PdfPageEventHelper {

		private final Letterhead lh;

		private final Image watermark;

		Decorations(Letterhead lh) {
			this.lh = lh;
			this.watermark = lh.style().watermark() ? image(lh.logo()) : null;
		}

		@Override
		public void onEndPage(PdfWriter writer, Document document) {
			Rectangle page = document.getPageSize();
			if (watermark != null) {
				try {
					PdfContentByte under = writer.getDirectContentUnder();
					under.saveState();
					PdfGState state = new PdfGState();
					state.setFillOpacity(0.06f);
					under.setGState(state);
					float size = Math.min(page.getWidth(), page.getHeight()) * 0.55f;
					watermark.scaleToFit(size, size);
					watermark.setAbsolutePosition((page.getWidth() - watermark.getScaledWidth()) / 2,
							(page.getHeight() - watermark.getScaledHeight()) / 2);
					under.addImage(watermark);
					under.restoreState();
				}
				catch (Exception ex) {
					// print without the watermark
				}
			}
			PdfContentByte cb = writer.getDirectContent();
			float y = document.bottom() - 16;
			cb.saveState();
			cb.setColorStroke(tint(lh.style().accent(), 0.45f));
			cb.setLineWidth(0.6f);
			cb.moveTo(document.left(), y + 9);
			cb.lineTo(document.right(), y + 9);
			cb.stroke();
			cb.restoreState();
			Font font = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
			String footer = lh.style().footer();
			String left = footer == null || footer.isBlank() ? nz(lh.schoolName()) : footer;
			ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(left, font), document.left(), y, 0);
			ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), font),
					document.right(), y, 0);
		}
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
