package com.selectcode.sms.students.app;

import com.selectcode.sms.families.FamilyService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.files.FileService;
import com.selectcode.sms.shared.pdf.CertificatePdf;
import com.selectcode.sms.shared.pdf.CertificateRegister;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.students.StudentService;
import com.selectcode.sms.students.domain.Student;
import java.awt.Color;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Student certificates (FR 5.8), ID cards (FR 5.8) and the bio-data sheet (FR 5.9), with the school letterhead. */
@Service
public class StudentPrintService {

	/** Optional details typed when issuing a certificate. */
	public record CertificateInput(String conduct, String reason, String remarks, String examination, String session,
			String seatNo, String result) {
	}

	private final StudentService students;

	private final FamilyService families;

	private final CertificateRegister register;

	private final LetterheadSource letterheads;

	private final FileService files;

	private final YearContext yearContext;

	private final StudentAccess access;

	private final AuditService audit;

	StudentPrintService(StudentService students, FamilyService families, CertificateRegister register,
			LetterheadSource letterheads, FileService files, YearContext yearContext, StudentAccess access,
			AuditService audit) {
		this.students = students;
		this.families = families;
		this.register = register;
		this.letterheads = letterheads;
		this.files = files;
		this.yearContext = yearContext;
		this.access = access;
		this.audit = audit;
	}

	@Transactional
	public byte[] certificate(long studentId, CertificateRegister.CertificateType type, CertificateInput in) {
		if (type == CertificateRegister.CertificateType.EXPERIENCE) {
			throw ApiException.businessRule("TYPE_INVALID", "Experience certificates are for staff");
		}
		var view = students.get(studentId);
		Student s = students.entity(studentId);
		if (type == CertificateRegister.CertificateType.LEAVING && !"LEFT".equals(s.getStatus())) {
			throw ApiException.businessRule("NOT_LEFT", "Record the withdrawal before printing a leaving certificate");
		}
		boolean girl = "FEMALE".equals(s.getGender());
		String child = girl ? "daughter" : "son";
		String he = girl ? "She" : "He";
		String his = girl ? "her" : "his";
		String father = families.fatherName(s.getFamilyId());
		String who = "%s, %s of %s, GR No. %s".formatted(s.getName(), child, father == null ? "—" : father, s.getGrNo());
		String className = view.currentClass() == null ? "—"
				: view.currentClass().className() + " (" + view.currentClass().sectionName() + ")";
		String dob = s.getDob() == null ? "—" : PdfKit.date(s.getDob()) + " (" + PdfKit.dateInWords(s.getDob()) + ")";
		String conduct = blank(in.conduct()) ? "good" : in.conduct().trim();
		List<String> paragraphs = new ArrayList<>();
		String title;
		switch (type) {
			case LEAVING -> {
				title = "SCHOOL LEAVING CERTIFICATE";
				paragraphs.add("This is to certify that %s, was a bonafide student of this school from %s to %s."
					.formatted(who, PdfKit.date(s.getAdmissionDate()), PdfKit.date(s.getLeftDate())));
				paragraphs.add("%s was studying in %s at the time of leaving. %s date of birth according to the school record is %s."
					.formatted(he, className, capital(his), dob));
				paragraphs.add("Reason for leaving: %s. %s conduct during the stay was %s."
					.formatted(blank(in.reason()) ? withdrawalReason(studentId) : in.reason().trim(), capital(his), conduct));
			}
			case DATE_OF_BIRTH -> {
				title = "DATE OF BIRTH CERTIFICATE";
				paragraphs.add("This is to certify that %s, %s a student of this school in %s."
					.formatted(who, "LEFT".equals(s.getStatus()) ? "was" : "is", className));
				paragraphs.add("According to the school admission record, %s date of birth is %s.".formatted(his, dob));
			}
			case CHARACTER -> {
				title = "CHARACTER CERTIFICATE";
				paragraphs.add("This is to certify that %s, %s a student of this school (admitted on %s)."
					.formatted(who, "LEFT".equals(s.getStatus()) ? "was" : "is", PdfKit.date(s.getAdmissionDate())));
				paragraphs.add("To the best of our knowledge %s bears a %s moral character.".formatted(he.toLowerCase(), conduct));
			}
			case APPRECIATION -> {
				title = "CERTIFICATE OF APPRECIATION";
				if (blank(in.reason())) {
					throw ApiException.businessRule("REASON_REQUIRED", "Describe what the certificate is awarded for");
				}
				paragraphs.add("This certificate is awarded to %s of %s in appreciation of %s."
					.formatted(who, className, in.reason().trim()));
			}
			case PROVISIONAL -> {
				title = "PROVISIONAL CERTIFICATE";
				if (blank(in.examination()) || blank(in.result())) {
					throw ApiException.businessRule("FIELD_REQUIRED", "Enter the examination and the result");
				}
				paragraphs.add("This is to certify that %s, appeared in the %s examination%s under Seat No. %s and passed with %s."
					.formatted(who, in.examination().trim(), blank(in.session()) ? "" : " held in " + in.session().trim(),
							blank(in.seatNo()) ? "—" : in.seatNo().trim(), in.result().trim()));
				paragraphs.add("This provisional certificate is issued pending the official certificate from the board.");
			}
			case HOPE -> {
				title = "HOPE CERTIFICATE";
				if (blank(in.examination())) {
					throw ApiException.businessRule("FIELD_REQUIRED", "Enter the examination");
				}
				paragraphs.add("This is to certify that %s, is a student of %s. %s has prepared well and we hope %s will pass the %s examination%s."
					.formatted(who, className, he, he.toLowerCase(), in.examination().trim(),
							blank(in.session()) ? "" : " to be held in " + in.session().trim()));
			}
			default -> throw ApiException.businessRule("TYPE_INVALID", "Unknown certificate type");
		}
		if (!blank(in.remarks())) {
			paragraphs.add(in.remarks().trim());
		}
		paragraphs.add("We wish %s every success in the future.".formatted(girl ? "her" : "him"));
		var issued = register.issue(type, studentId, null, Map.of("paragraphs", paragraphs, "title", title));
		audit.record("CERTIFICATE_ISSUED", "student", studentId, null, Map.of("type", type.name(), "serialNo",
				issued.serialNo()));
		return CertificatePdf.render(letterheads.current(), new CertificatePdf.CertificateContent(title, issued.serialNo(),
				access.today(), paragraphs, List.of("Principal")));
	}

	/** Bio-data sheet of one student (FR 5.9). */
	@Transactional(readOnly = true)
	public byte[] bioData(long studentId) {
		var view = students.get(studentId);
		var d = view.details();
		var family = families.get(view.family().id());
		var fd = family.details();
		Letterhead lh = letterheads.current();
		byte[] photo = files.content(d.photoFileId()).orElse(null);
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "STUDENT BIO-DATA");
			if (photo != null) {
				PdfPTable t = new PdfPTable(1);
				t.setWidthPercentage(20);
				t.setHorizontalAlignment(Element.ALIGN_RIGHT);
				t.addCell(PdfKit.imageCell(photo, 90));
				PdfKit.add(doc, t);
			}
			List<String[]> rows = new ArrayList<>();
			rows.add(new String[] { "GR No.", view.grNo() });
			rows.add(new String[] { "Name", d.name() });
			rows.add(new String[] { "Gender", "FEMALE".equals(d.gender()) ? "Female" : "Male" });
			rows.add(new String[] { "Date of birth", d.dob() == null ? "—" : PdfKit.date(d.dob()) });
			rows.add(new String[] { "Place of birth", d.placeOfBirth() });
			rows.add(new String[] { "B-Form No.", view.privateHidden() ? "(hidden)" : d.bFormNo() });
			rows.add(new String[] { "Religion / Nationality", PdfKit.nz(d.religion()) + " / " + PdfKit.nz(d.nationality()) });
			rows.add(new String[] { "Class", view.currentClass() == null ? "—"
					: view.currentClass().className() + " - " + view.currentClass().sectionName() });
			rows.add(new String[] { "Admission date", PdfKit.date(view.admissionDate()) });
			rows.add(new String[] { "Previous school", d.previousSchool() });
			rows.add(new String[] { "Family No.", family.familyNo() });
			rows.add(new String[] { "Father", PdfKit.nz(fd.fatherName()) + (fd.fatherProfession() == null ? "" : " (" + fd.fatherProfession() + ")") });
			rows.add(new String[] { "Mother", fd.motherName() });
			if (!view.privateHidden()) {
				rows.add(new String[] { "Phone", PdfKit.nz(fd.fatherPhone()) + (fd.motherPhone() == null ? "" : ", " + fd.motherPhone()) });
				rows.add(new String[] { "Address", d.address() == null ? fd.address() : d.address() });
			}
			rows.add(new String[] { "Status", "LEFT".equals(view.status()) ? "Left on " + PdfKit.date(view.leftDate()) : "Active" });
			PdfKit.details(doc, rows);
			PdfKit.line(doc, "Printed " + PdfKit.date(access.today()), "");
		});
	}

	/** ID cards, 8 per A4 page (FR 5.8), valid until the end of the selected academic year. */
	@Transactional(readOnly = true)
	public byte[] idCards(List<Long> studentIds) {
		if (studentIds.isEmpty() || studentIds.size() > 400) {
			throw ApiException.businessRule("COUNT_INVALID", "Choose between 1 and 400 students");
		}
		var year = yearContext.requireSelected();
		Letterhead lh = letterheads.current();
		Font name = new Font(Font.HELVETICA, 10, Font.BOLD);
		Font small = new Font(Font.HELVETICA, 8);
		Font school = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
		return PdfKit.render(PageSize.A4, 25, doc -> {
			PdfPTable grid = new PdfPTable(2);
			grid.setWidthPercentage(100);
			for (Long id : studentIds) {
				var view = students.get(id);
				var cls = view.currentClass();
				PdfPTable card = new PdfPTable(new float[] { 1f, 2.2f });
				card.setWidthPercentage(100);
				PdfPCell header = new PdfPCell(new Phrase(PdfKit.nz(lh.schoolName()), school));
				header.setColspan(2);
				header.setBackgroundColor(new Color(20, 40, 90));
				header.setHorizontalAlignment(Element.ALIGN_CENTER);
				header.setPadding(4);
				card.addCell(header);
				PdfPCell photo = PdfKit.imageCell(files.content(view.details().photoFileId()).orElse(null), 60);
				photo.setFixedHeight(75);
				card.addCell(photo);
				PdfPCell info = new PdfPCell();
				info.setBorder(Rectangle.NO_BORDER);
				info.addElement(new Paragraph(view.details().name(), name));
				info.addElement(new Paragraph("GR No. " + view.grNo(), small));
				info.addElement(new Paragraph("Class: " + (cls == null ? "—" : cls.className() + " - " + cls.sectionName()), small));
				info.addElement(new Paragraph("Father: " + PdfKit.nz(view.family().fatherName()), small));
				info.addElement(new Paragraph("Valid till: " + PdfKit.date(year.endDate()), small));
				card.addCell(info);
				PdfPCell outer = new PdfPCell(card);
				outer.setPadding(6);
				outer.setFixedHeight(185);
				grid.addCell(outer);
			}
			if (studentIds.size() % 2 == 1) {
				grid.addCell(PdfKit.empty());
			}
			PdfKit.add(doc, grid);
		});
	}

	private String withdrawalReason(long studentId) {
		return students.leavingReason(studentId).orElse("not recorded");
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String capital(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
