package com.selectcode.sms.exams.app;

import com.selectcode.sms.exams.app.ExamSetupService.ExamClassView;
import com.selectcode.sms.exams.app.ExamSetupService.PaperView;
import com.selectcode.sms.exams.app.ResultService.ClassResults;
import com.selectcode.sms.exams.app.ResultService.PaperResult;
import com.selectcode.sms.exams.app.ResultService.StudentResult;
import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.web.ApiException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exam printouts (FR 14.3, 14.5, 14.10): the datesheet (PDF, or a PNG for WhatsApp groups), admit cards, the class
 * result sheet and report cards. Report cards and the result sheet of a published class come from the frozen
 * snapshot, so a reprint matches what was published.
 */
@Service
public class ExamDocuments {

	private static final org.openpdf.text.Font CELL = new org.openpdf.text.Font(org.openpdf.text.Font.HELVETICA, 9);

	private static final org.openpdf.text.Font CELL_BOLD = new org.openpdf.text.Font(org.openpdf.text.Font.HELVETICA, 9,
			org.openpdf.text.Font.BOLD);

	private static final Color HEAD = new Color(243, 244, 246);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd-MM-yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	private final JdbcTemplate jdbc;

	private final ExamSetupService setup;

	private final ResultService results;

	private final MarksService marks;

	private final SettingsService settings;

	private final LetterheadSource letterhead;

	ExamDocuments(JdbcTemplate jdbc, ExamSetupService setup, ResultService results, MarksService marks,
			SettingsService settings, LetterheadSource letterhead) {
		this.jdbc = jdbc;
		this.setup = setup;
		this.results = results;
		this.marks = marks;
		this.settings = settings;
		this.letterhead = letterhead;
	}

	// ------------------------------------------------------------ datesheet

	@Transactional(readOnly = true)
	public byte[] datesheetPdf(long examClassId) {
		ExamClassView ec = setup.examClass(examClassId);
		Letterhead lh = letterhead.current();
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "DATESHEET - " + ec.examName() + " - " + ec.className());
			PdfPTable t = new PdfPTable(new float[] { 3f, 3f, 3f, 5f });
			t.setWidthPercentage(100);
			for (String h : List.of("Date", "Time", "Subject", "Syllabus")) {
				cell(t, h, CELL_BOLD, HEAD);
			}
			for (PaperView p : dated(ec)) {
				cell(t, p.paperDate() == null ? "To be announced" : DAY.format(p.paperDate()), CELL, null);
				cell(t, times(p), CELL, null);
				cell(t, p.subjectName(), CELL_BOLD, null);
				cell(t, p.syllabus(), CELL, null);
			}
			PdfKit.add(doc, t);
			PdfKit.signatures(doc, lh, List.of("Exam coordinator", "Principal"), false);
		});
	}

	/** The datesheet as an image for sharing in parent WhatsApp groups. */
	@Transactional(readOnly = true)
	public byte[] datesheetPng(long examClassId) {
		ExamClassView ec = setup.examClass(examClassId);
		Letterhead lh = letterhead.current();
		List<PaperView> papers = dated(ec);
		int width = 1000;
		int rowH = 46;
		int top = 170;
		int height = top + rowH * (papers.size() + 1) + 60;
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.setColor(new Color(20, 40, 90));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
			centre(g, PdfKit.nz(lh.schoolName()), width, 60);
			g.setColor(Color.DARK_GRAY);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
			centre(g, PdfKit.nz(lh.campusName()), width, 92);
			g.setColor(Color.BLACK);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
			centre(g, "Datesheet: " + ec.examName() + " - " + ec.className(), width, 138);
			int[] cols = { 30, 290, 500, 970 };
			g.setColor(HEAD);
			g.fillRect(cols[0], top, cols[3] - cols[0], rowH);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
			g.setColor(Color.BLACK);
			String[] heads = { "Date", "Time", "Subject" };
			for (int i = 0; i < 3; i++) {
				g.drawString(heads[i], cols[i] + 12, top + 30);
			}
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
			for (int r = 0; r < papers.size(); r++) {
				PaperView p = papers.get(r);
				int y = top + rowH * (r + 1);
				g.drawString(p.paperDate() == null ? "To be announced" : DAY.format(p.paperDate()), cols[0] + 12, y + 30);
				g.drawString(times(p), cols[1] + 12, y + 30);
				g.drawString(p.subjectName(), cols[2] + 12, y + 30);
			}
			g.setColor(Color.GRAY);
			g.setStroke(new BasicStroke(1.2f));
			for (int r = 0; r <= papers.size() + 1; r++) {
				g.drawLine(cols[0], top + rowH * r, cols[3], top + rowH * r);
			}
			for (int x : cols) {
				g.drawLine(x, top, x, top + rowH * (papers.size() + 1));
			}
		}
		finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(img, "png", out);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toByteArray();
	}

	private static void centre(Graphics2D g, String text, int width, int y) {
		FontMetrics m = g.getFontMetrics();
		g.drawString(text, (width - m.stringWidth(text)) / 2, y);
	}

	private static List<PaperView> dated(ExamClassView ec) {
		List<PaperView> papers = new ArrayList<>(ec.papers());
		papers.sort(java.util.Comparator
			.comparing(PaperView::paperDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
			.thenComparing(PaperView::startTime, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
			.thenComparing(PaperView::displayOrder));
		return papers;
	}

	private static String times(PaperView p) {
		if (p.startTime() == null) {
			return "";
		}
		return TIME.format(p.startTime()) + (p.endTime() == null ? "" : " - " + TIME.format(p.endTime()));
	}

	// ----------------------------------------------------------- admit cards

	/**
	 * Admit cards (roll number slips), four to a page. With {@code withheldDefaulters}, students with fees overdue are
	 * listed separately instead of getting a card (the school decides whether to use this).
	 */
	@Transactional(readOnly = true)
	public byte[] admitCards(long examClassId, Long sectionId, boolean withheldDefaulters) {
		ExamClassView ec = setup.examClass(examClassId);
		Letterhead lh = letterhead.current();
		List<long[]> students = marks.students(examClassId, sectionId);
		if (students.isEmpty()) {
			throw ApiException.businessRule("NO_STUDENTS", "There are no students in this class or section");
		}
		Set<Long> defaulters = new HashSet<>();
		if (withheldDefaulters) {
			defaulters.addAll(jdbc.queryForList("""
					select distinct v.student_id from fee_line_status v
					where v.balance > 0 and v.due_date < current_date""", Long.class));
		}
		List<PaperView> papers = dated(ec);
		List<String[]> held = new ArrayList<>();
		return PdfKit.a4(doc -> {
			PdfPTable grid = new PdfPTable(2);
			grid.setWidthPercentage(100);
			int cards = 0;
			for (long[] s : students) {
				var st = jdbc.queryForMap("""
						select st.gr_no, st.name, f.father_name,
						       (select e.roll_no from enrolment e where e.student_id = st.id and e.section_id = ?
						        order by e.effective_from desc limit 1) as roll_no
						from student st join family f on f.id = st.family_id where st.id = ?""", s[1], s[0]);
				String section = sectionLabel(s[1]);
				if (defaulters.contains(s[0])) {
					held.add(new String[] { (String) st.get("gr_no"), (String) st.get("name"), section });
					continue;
				}
				PdfPCell card = new PdfPCell();
				card.setPadding(8);
				card.setFixedHeight(360);
				Paragraph school = new Paragraph(PdfKit.nz(lh.schoolName()), PdfKit.HEADING);
				school.setAlignment(Element.ALIGN_CENTER);
				card.addElement(school);
				Paragraph title = new Paragraph("ADMIT CARD - " + ec.examName(), CELL_BOLD);
				title.setAlignment(Element.ALIGN_CENTER);
				title.setSpacingAfter(6);
				card.addElement(title);
				for (String[] row : List.of(new String[] { "Name", (String) st.get("name") },
						new String[] { "Father", (String) st.get("father_name") },
						new String[] { "GR no.", (String) st.get("gr_no") },
						new String[] { "Class", section },
						new String[] { "Roll no.", (String) st.get("roll_no") })) {
					card.addElement(new Paragraph(row[0] + ": " + PdfKit.nz(row[1]), CELL));
				}
				PdfPTable ds = new PdfPTable(new float[] { 3f, 4f });
				ds.setWidthPercentage(100);
				ds.setSpacingBefore(6);
				for (PaperView p : papers) {
					cell(ds, p.paperDate() == null ? "" : PdfKit.date(p.paperDate()), CELL, null);
					cell(ds, p.subjectName(), CELL, null);
				}
				card.addElement(ds);
				Paragraph sign = new Paragraph("\n______________\nPrincipal", CELL);
				sign.setAlignment(Element.ALIGN_RIGHT);
				card.addElement(sign);
				grid.addCell(card);
				cards++;
			}
			if (cards % 2 == 1) {
				grid.addCell(PdfKit.empty());
			}
			if (cards > 0) {
				PdfKit.add(doc, grid);
			}
			if (!held.isEmpty()) {
				doc.newPage();
				PdfKit.title(doc, "Admit cards withheld (fees overdue)");
				PdfPTable t = new PdfPTable(new float[] { 2f, 5f, 3f });
				t.setWidthPercentage(100);
				for (String h : List.of("GR no.", "Name", "Class")) {
					cell(t, h, CELL_BOLD, HEAD);
				}
				for (String[] h : held) {
					for (String v : h) {
						cell(t, v, CELL, null);
					}
				}
				PdfKit.add(doc, t);
			}
		});
	}

	private String sectionLabel(long sectionId) {
		return jdbc.queryForObject("""
				select c.name || ' - ' || s.name from section s join school_class c on c.id = s.class_id where s.id = ?""",
				String.class, sectionId);
	}

	// ----------------------------------------------------------- result sheet

	/** The class result sheet (tabulation sheet): one row per student, a column per paper. */
	@Transactional(readOnly = true)
	public byte[] resultSheet(long examClassId, Long sectionId) {
		ExamClassView ec = setup.examClass(examClassId);
		ClassResults r = results.results(examClassId, sectionId, null);
		boolean gradeOnly = "GRADES".equals(ec.gradingMode());
		Letterhead lh = letterhead.current();
		List<PaperView> papers = ec.papers();
		return PdfKit.render(PageSize.A4.rotate(), 20, doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "RESULT SHEET - " + ec.examName() + " - " + ec.className()
					+ (r.live() ? " (draft, not published)" : ""));
			int extra = gradeOnly ? 1 : 5;
			float[] widths = new float[3 + papers.size() + extra];
			java.util.Arrays.fill(widths, 1.4f);
			widths[0] = 1.6f;
			widths[1] = 4.5f;
			widths[2] = 1.8f;
			PdfPTable t = new PdfPTable(widths);
			t.setWidthPercentage(100);
			t.setHeaderRows(1);
			cell(t, "GR", CELL_BOLD, HEAD);
			cell(t, "Name", CELL_BOLD, HEAD);
			cell(t, "Section", CELL_BOLD, HEAD);
			for (PaperView p : papers) {
				cell(t, p.subjectName() + (gradeOnly ? "" : " (" + p.maxMarks() + ")"), CELL_BOLD, HEAD);
			}
			if (!gradeOnly) {
				for (String h : List.of("Total", "%", "Grade", "Position")) {
					cell(t, h, CELL_BOLD, HEAD);
				}
			}
			cell(t, "Result", CELL_BOLD, HEAD);
			for (StudentResult s : r.students()) {
				cell(t, s.grNo(), CELL, null);
				cell(t, s.name(), CELL, null);
				cell(t, s.section(), CELL, null);
				for (PaperResult p : s.papers()) {
					cell(t, paperText(p, gradeOnly), CELL, Boolean.FALSE.equals(p.passed()) ? new Color(254, 226, 226) : null);
				}
				if (!gradeOnly) {
					cell(t, s.totalObtained() == null ? "" : s.totalObtained() + "/" + s.totalMax(), CELL, null);
					cell(t, s.percent(), CELL, null);
					cell(t, s.grade(), CELL_BOLD, null);
					cell(t, s.position() == null ? "" : String.valueOf(s.position()), CELL, null);
				}
				cell(t, resultText(s), CELL_BOLD, null);
			}
			PdfKit.add(doc, t);
			PdfKit.signatures(doc, lh, List.of("Class teacher", "Exam coordinator", "Principal"), false);
		});
	}

	private static String paperText(PaperResult p, boolean gradeOnly) {
		if ("ABSENT".equals(p.flag())) {
			return "Abs";
		}
		if ("EXEMPT".equals(p.flag())) {
			return "Ex";
		}
		return gradeOnly ? PdfKit.nz(p.grade()) : PdfKit.nz(p.obtained());
	}

	private static String resultText(StudentResult s) {
		if (s.missing() > 0) {
			return "Incomplete";
		}
		return s.passed() == null ? "" : s.passed() ? "Pass" : "Fail";
	}

	// ----------------------------------------------------------- report cards

	/**
	 * Report cards, one page per student, for a student, a section or the whole class (QA-EXM-010/011). Published
	 * classes print from the frozen snapshot; a draft prints only for checking and is marked so.
	 */
	@Transactional(readOnly = true)
	public byte[] reportCards(long examClassId, Long sectionId, Long studentId) {
		ExamClassView ec = setup.examClass(examClassId);
		ClassResults r = results.results(examClassId, sectionId, null);
		List<StudentResult> students = r.students()
			.stream()
			.filter(s -> studentId == null || studentId == s.studentId())
			.toList();
		if (students.isEmpty()) {
			throw ApiException.notFound("Student");
		}
		boolean compact = "COMPACT".equals(settings.text(SettingKey.REPORT_CARD_LAYOUT));
		boolean highest = settings.bool(SettingKey.SHOW_HIGHEST_MARKS_COLUMN);
		boolean principalSignature = settings.bool(SettingKey.PRINCIPAL_SIGNATURE_ON_MARKSHEET);
		boolean gradeOnly = "GRADES".equals(ec.gradingMode());
		Letterhead lh = letterhead.current();
		String comments = jdbc.queryForObject("select comments from exam where id = ?", String.class, ec.examId());
		return PdfKit.a4(doc -> {
			boolean first = true;
			for (StudentResult s : students) {
				if (!first) {
					doc.newPage();
				}
				first = false;
				PdfKit.letterhead(doc, lh);
				PdfKit.title(doc, "REPORT CARD - " + ec.examName() + (r.live() ? " (DRAFT - NOT PUBLISHED)" : ""));
				studentBox(doc, s, compact);
				if (compact) {
					compactBody(doc, s, gradeOnly);
				}
				else {
					classicBody(doc, s, gradeOnly, highest);
				}
				remarks(doc, s, comments);
				List<String> roles = new ArrayList<>(List.of("Class teacher"));
				if (principalSignature) {
					roles.add("Principal");
				}
				PdfKit.signatures(doc, lh, roles, false);
				if (!r.live()) {
					Paragraph v = new Paragraph("Published version " + r.version(), PdfKit.SMALL);
					v.setAlignment(Element.ALIGN_RIGHT);
					PdfKit.add(doc, v);
				}
			}
		});
	}

	private static void studentBox(Document doc, StudentResult s, boolean compact) {
		PdfPTable t = new PdfPTable(4);
		t.setWidthPercentage(100);
		t.setSpacingAfter(10);
		labelled(t, "Name", s.name());
		labelled(t, "GR no.", s.grNo());
		labelled(t, "Class", s.section());
		labelled(t, "Roll no.", s.rollNo());
		if (!compact && s.attendancePercent() != null) {
			labelled(t, "Attendance", s.attendancePercent() + "%");
			labelled(t, "", "");
		}
		PdfKit.add(doc, t);
	}

	private static void labelled(PdfPTable t, String label, String value) {
		PdfPCell l = new PdfPCell(new Phrase(label, CELL_BOLD));
		l.setBackgroundColor(HEAD);
		l.setPadding(4);
		PdfPCell v = new PdfPCell(new Phrase(PdfKit.nz(value), CELL));
		v.setPadding(4);
		t.addCell(l);
		t.addCell(v);
	}

	private static void classicBody(Document doc, StudentResult s, boolean gradeOnly, boolean highest) {
		List<String> heads = new ArrayList<>(List.of("Subject"));
		if (gradeOnly) {
			heads.add("Grade");
		}
		else {
			heads.addAll(List.of("Maximum", "Passing", "Obtained", "%", "Grade"));
			if (highest) {
				heads.add("Highest");
			}
		}
		heads.add("Remark");
		float[] widths = new float[heads.size()];
		java.util.Arrays.fill(widths, 1.3f);
		widths[0] = 3.5f;
		PdfPTable t = new PdfPTable(widths);
		t.setWidthPercentage(100);
		heads.forEach(h -> cell(t, h, CELL_BOLD, HEAD));
		for (PaperResult p : s.papers()) {
			cell(t, p.subject(), CELL_BOLD, null);
			if (gradeOnly) {
				cell(t, "EXEMPT".equals(p.flag()) ? "Exempt" : "ABSENT".equals(p.flag()) ? "Absent" : p.grade(), CELL, null);
			}
			else {
				cell(t, p.maxMarks(), CELL, null);
				cell(t, p.passingMarks(), CELL, null);
				cell(t, "ABSENT".equals(p.flag()) ? "Absent" : "EXEMPT".equals(p.flag()) ? "Exempt" : p.obtained(), CELL,
						null);
				cell(t, p.percent(), CELL, null);
				cell(t, p.grade(), CELL, null);
				if (highest) {
					cell(t, p.highest(), CELL, null);
				}
			}
			cell(t, p.passed() == null ? "" : p.passed() ? "Pass" : "Fail", CELL,
					Boolean.FALSE.equals(p.passed()) ? new Color(254, 226, 226) : null);
		}
		PdfKit.add(doc, t);
		if (!gradeOnly) {
			PdfPTable sum = new PdfPTable(4);
			sum.setWidthPercentage(100);
			sum.setSpacingBefore(8);
			labelled(sum, "Total", s.totalObtained() == null ? "" : s.totalObtained() + " / " + s.totalMax());
			labelled(sum, "Percentage", s.percent() == null ? "" : s.percent() + "%");
			labelled(sum, "Grade", join(s.grade(), s.gradeRemark()));
			labelled(sum, "Result", resultText(s));
			if (s.position() != null) {
				labelled(sum, "Position", String.valueOf(s.position()));
				labelled(sum, "", "");
			}
			PdfKit.add(doc, sum);
		}
		else {
			PdfPTable sum = new PdfPTable(2);
			sum.setWidthPercentage(50);
			sum.setSpacingBefore(8);
			labelled(sum, "Result", resultText(s));
			PdfKit.add(doc, sum);
		}
	}

	private static void compactBody(Document doc, StudentResult s, boolean gradeOnly) {
		PdfPTable outer = new PdfPTable(new float[] { 6f, 3f });
		outer.setWidthPercentage(100);
		PdfPTable marksTable = new PdfPTable(gradeOnly ? new float[] { 4f, 2f } : new float[] { 4f, 2f, 2f });
		marksTable.setWidthPercentage(100);
		cell(marksTable, "Subject", CELL_BOLD, HEAD);
		if (!gradeOnly) {
			cell(marksTable, "Marks", CELL_BOLD, HEAD);
		}
		cell(marksTable, "Grade", CELL_BOLD, HEAD);
		for (PaperResult p : s.papers()) {
			cell(marksTable, p.subject(), CELL, null);
			if (!gradeOnly) {
				cell(marksTable, "ABSENT".equals(p.flag()) ? "Abs" : "EXEMPT".equals(p.flag()) ? "Ex"
						: PdfKit.nz(p.obtained()) + " / " + p.maxMarks(), CELL,
						Boolean.FALSE.equals(p.passed()) ? new Color(254, 226, 226) : null);
			}
			cell(marksTable, "EXEMPT".equals(p.flag()) ? "Ex" : p.grade(), CELL, null);
		}
		PdfPCell left = new PdfPCell(marksTable);
		left.setBorder(Rectangle.NO_BORDER);
		left.setPaddingRight(8);
		outer.addCell(left);
		PdfPCell box = new PdfPCell();
		box.setPadding(8);
		box.setBackgroundColor(new Color(248, 250, 252));
		if (!gradeOnly) {
			box.addElement(new Paragraph("Total: " + PdfKit.nz(s.totalObtained()) + " / " + PdfKit.nz(s.totalMax()),
					CELL_BOLD));
			box.addElement(new Paragraph("Percentage: " + PdfKit.nz(s.percent()) + "%", CELL_BOLD));
			box.addElement(new Paragraph("Grade: " + join(s.grade(), s.gradeRemark()), CELL_BOLD));
			if (s.position() != null) {
				box.addElement(new Paragraph("Position: " + s.position(), CELL_BOLD));
			}
		}
		box.addElement(new Paragraph("Result: " + resultText(s), CELL_BOLD));
		if (s.attendancePercent() != null) {
			box.addElement(new Paragraph("Attendance: " + s.attendancePercent() + "%", CELL));
		}
		outer.addCell(box);
		PdfKit.add(doc, outer);
	}

	private static void remarks(Document doc, StudentResult s, String comments) {
		PdfPTable t = new PdfPTable(new float[] { 2f, 6f });
		t.setWidthPercentage(100);
		t.setSpacingBefore(12);
		labelledWide(t, "Class teacher's remarks", s.teacherRemark());
		labelledWide(t, "Principal's remarks", s.principalRemark());
		if (comments != null && !comments.isBlank()) {
			labelledWide(t, "Note", comments);
		}
		PdfKit.add(doc, t);
	}

	private static void labelledWide(PdfPTable t, String label, String value) {
		PdfPCell l = new PdfPCell(new Phrase(label, CELL_BOLD));
		l.setBackgroundColor(HEAD);
		l.setPadding(5);
		PdfPCell v = new PdfPCell(new Phrase(PdfKit.nz(value), CELL));
		v.setPadding(5);
		v.setMinimumHeight(28);
		t.addCell(l);
		t.addCell(v);
	}

	private static String join(String grade, String remark) {
		return PdfKit.nz(grade) + (remark == null || remark.isBlank() ? "" : " (" + remark + ")");
	}

	private static void cell(PdfPTable t, String text, org.openpdf.text.Font font, Color background) {
		PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
		c.setPadding(4);
		if (background != null) {
			c.setBackgroundColor(background);
		}
		t.addCell(c);
	}

	/** Used by the result SMS: "Total 412/500 (82.40%), Grade A, Pass". */
	static String smsSummary(StudentResult s) {
		if (s.percent() == null) {
			return "Grade-only result: " + resultText(s);
		}
		return "Total " + s.totalObtained() + "/" + s.totalMax() + " (" + new BigDecimal(s.percent()).toPlainString()
				+ "%), Grade " + s.grade() + (s.position() == null ? "" : ", Position " + s.position()) + ", "
				+ resultText(s);
	}
}
