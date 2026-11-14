package com.tirvextech.sms.admissions.app;

import com.tirvextech.sms.academics.AcademicStructure;
import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.pdf.LetterheadSource;
import com.tirvextech.sms.shared.pdf.PdfKit;
import com.tirvextech.sms.shared.tenant.TenantContext;
import com.tirvextech.sms.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission terms, entry-test syllabus per class and the printable admission form (FR 4.11). */
@Service
public class AdmissionDocuments {

	public record AdmissionContent(String kind, Long classId, String body) {
	}

	private final JdbcTemplate jdbc;

	private final AcademicStructure structure;

	private final AdmissionsService admissions;

	private final LetterheadSource letterheads;

	private final AuditService audit;

	private final SettingsService settings;

	AdmissionDocuments(JdbcTemplate jdbc, AcademicStructure structure, AdmissionsService admissions,
			LetterheadSource letterheads, AuditService audit, SettingsService settings) {
		this.settings = settings;
		this.jdbc = jdbc;
		this.structure = structure;
		this.admissions = admissions;
		this.letterheads = letterheads;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<AdmissionContent> list() {
		return jdbc.query("select kind, class_id, body from admission_content order by kind, class_id nulls first",
				(rs, i) -> new AdmissionContent(rs.getString(1), (Long) rs.getObject(2), rs.getString(3)));
	}

	/** Terms apply to all classes (classId null); syllabus is per class. Empty text removes it. */
	@Transactional
	public void save(String kind, Long classId, String body) {
		if (!Set.of("TERMS", "SYLLABUS").contains(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose terms or syllabus");
		}
		if ("SYLLABUS".equals(kind) && classId == null) {
			throw ApiException.businessRule("CLASS_REQUIRED", "Choose the class for the syllabus");
		}
		if (classId != null) {
			structure.requireClass(classId);
		}
		jdbc.update("delete from admission_content where kind = ? and coalesce(class_id, 0) = ?", kind,
				classId == null ? 0 : classId);
		if (body != null && !body.isBlank()) {
			if (body.length() > 20_000) {
				throw ApiException.businessRule("TOO_LONG", "At most 20,000 characters");
			}
			jdbc.update("insert into admission_content (school_id, kind, class_id, body) values (?, ?, ?, ?)",
					TenantContext.requireSchoolId(), kind, classId, body.trim());
		}
		audit.record("ADMISSION_CONTENT_SAVED", "admission_content", classId, null, Map.of("kind", kind));
	}

	/**
	 * Admission form with the school heading, pre-filled from an enquiry if given, room to write the rest by hand, and
	 * the terms. Which parts it has (photo box, mother, emergency contact, medical, documents, office use) is set in
	 * Rules &amp; settings, Admission form.
	 */
	@Transactional(readOnly = true)
	public byte[] form(Long enquiryId, Long classId) {
		var lh = letterheads.current();
		Optional<AdmissionsService.EnquiryView> enquiry = enquiryId == null ? Optional.empty()
				: Optional.of(admissions.get(enquiryId));
		Long cls = classId != null ? classId : enquiry.map(e -> e.details().classAppliedId()).orElse(null);
		String terms = text("TERMS", null);
		String syllabus = cls == null ? null : text("SYLLABUS", cls);
		String className = cls == null ? "" : structure.findClass(cls).map(AcademicStructure.ClassRef::name).orElse("");
		boolean photo = settings.bool(SettingKey.ADMISSION_FORM_PHOTO);
		boolean mother = settings.bool(SettingKey.ADMISSION_FORM_MOTHER);
		boolean emergency = settings.bool(SettingKey.ADMISSION_FORM_EMERGENCY);
		boolean medical = settings.bool(SettingKey.ADMISSION_FORM_MEDICAL);
		boolean office = settings.bool(SettingKey.ADMISSION_FORM_OFFICE_USE);
		List<String> documents = java.util.Arrays.stream(PdfKit.nz(settings.text(SettingKey.ADMISSION_FORM_DOCUMENTS)).split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.toList();
		var d = enquiry.map(AdmissionsService.EnquiryView::details).orElse(null);
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "Admission form");
			top(doc, photo, new String[][] {
					{ "Registration no.", enquiry.map(AdmissionsService.EnquiryView::registrationNo).orElse("") },
					{ "Class applied for", className },
					{ "Date", d == null || d.enquiryDate() == null ? "" : PdfKit.date(d.enquiryDate()) } });

			PdfKit.section(doc, "Student information");
			List<String[]> student = new ArrayList<>();
			student.add(new String[] { "Full name*", d == null ? "" : PdfKit.nz(d.childName()) });
			student.add(new String[] { "Gender", d == null || d.gender() == null ? "" : ("FEMALE".equals(d.gender()) ? "Girl" : "Boy") });
			student.add(new String[] { "Date of birth", d == null || d.dob() == null ? "" : PdfKit.date(d.dob()) });
			student.add(new String[] { "Place of birth", "" });
			student.add(new String[] { "B-Form no.", "" });
			student.add(new String[] { "Religion", "" });
			student.add(new String[] { "Nationality", "Pakistani" });
			student.add(new String[] { "Previous school*", d == null ? "" : PdfKit.nz(d.previousSchool()) });
			student.add(new String[] { "Last class passed", "" });
			student.add(new String[] { "Reason for leaving", "" });
			PdfKit.fields(doc, student, 2);

			PdfKit.section(doc, "Father / guardian");
			PdfKit.fields(doc, List.of(new String[] { "Name*", d == null ? "" : PdfKit.nz(d.parentName()) },
					new String[] { "CNIC no.", d == null ? "" : PdfKit.nz(d.parentCnic()) },
					new String[] { "Profession", "" }, new String[] { "Mobile", d == null ? "" : PdfKit.nz(d.parentPhone()) },
					new String[] { "WhatsApp", "" }, new String[] { "Email*", "" }), 2);

			if (mother) {
				PdfKit.section(doc, "Mother");
				PdfKit.fields(doc, List.of(new String[] { "Name", "" }, new String[] { "CNIC no.", "" },
						new String[] { "Profession", "" }, new String[] { "Mobile", "" }), 2);
			}

			PdfKit.section(doc, "Address" + (emergency ? " and emergency contact" : ""));
			List<String[]> address = new ArrayList<>();
			address.add(new String[] { "Home address*", d == null ? "" : PdfKit.nz(d.address()) });
			if (emergency) {
				address.add(new String[] { "Emergency contact", "" });
				address.add(new String[] { "Relation", "" });
				address.add(new String[] { "Phone*", "" });
			}
			PdfKit.fields(doc, address, 2);

			if (medical) {
				PdfKit.section(doc, "Medical information");
				PdfKit.fields(doc, List.of(new String[] { "Blood group", "" }, new String[] { "Family doctor", "" },
						new String[] { "Allergies / conditions*", "" }), 2);
			}

			if (!documents.isEmpty()) {
				PdfKit.section(doc, "Documents attached");
				PdfKit.checklist(doc, documents, 3);
			}
			if (syllabus != null) {
				PdfKit.box(doc, "Entry test syllabus" + (className.isBlank() ? "" : " (" + className + ")"), syllabus);
			}
			if (terms != null) {
				PdfKit.box(doc, "Terms and conditions", terms);
			}

			PdfKit.section(doc, "Declaration");
			PdfKit.paragraph(doc, "I confirm that the information above is correct and I accept the school's terms and "
					+ "conditions, rules and fee policy.");
			PdfKit.line(doc, "Parent / guardian signature: ________________", "Date: ______________");

			if (office) {
				PdfKit.section(doc, "For office use only");
				PdfKit.fields(doc, List.of(new String[] { "GR no.", "" }, new String[] { "Admission date", "" },
						new String[] { "Class / section", "" }, new String[] { "Entry test result", "" },
						new String[] { "Admission fee (Rs.)", "" }, new String[] { "Receipt no.", "" },
						new String[] { "Checked by", "" }, new String[] { "Remarks", "" }), 2);
			}
			PdfKit.signatures(doc, lh, List.of("Principal"), false);
		});
	}

	/** The first rows of the form with a photo box at the right. */
	private static void top(org.openpdf.text.Document doc, boolean photo, String[][] rows) {
		org.openpdf.text.pdf.PdfPTable outer = new org.openpdf.text.pdf.PdfPTable(photo ? new float[] { 5f, 1.3f } : new float[] { 1f });
		outer.setWidthPercentage(100);
		org.openpdf.text.pdf.PdfPTable left = new org.openpdf.text.pdf.PdfPTable(new float[] { 1.35f, 4f });
		left.setWidthPercentage(100);
		java.awt.Color bg = PdfKit.tint(PdfKit.accent(), 0.07f);
		for (String[] r : rows) {
			org.openpdf.text.pdf.PdfPCell l = new org.openpdf.text.pdf.PdfPCell(new org.openpdf.text.Phrase(r[0], PdfKit.LABEL));
			l.setBackgroundColor(bg);
			l.setBorderColor(PdfKit.BORDER);
			l.setPadding(5);
			org.openpdf.text.pdf.PdfPCell v = new org.openpdf.text.pdf.PdfPCell(new org.openpdf.text.Phrase(PdfKit.nz(r[1]), PdfKit.BODY_BOLD));
			v.setBorderColor(PdfKit.BORDER);
			v.setPadding(5);
			v.setMinimumHeight(32);
			v.setVerticalAlignment(org.openpdf.text.Element.ALIGN_MIDDLE);
			l.setVerticalAlignment(org.openpdf.text.Element.ALIGN_MIDDLE);
			left.addCell(l);
			left.addCell(v);
		}
		org.openpdf.text.pdf.PdfPCell holder = new org.openpdf.text.pdf.PdfPCell(left);
		holder.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
		holder.setPadding(0);
		holder.setPaddingRight(photo ? 10 : 0);
		outer.addCell(holder);
		if (photo) {
			org.openpdf.text.pdf.PdfPCell box = new org.openpdf.text.pdf.PdfPCell(new org.openpdf.text.Phrase(
					"Paste a recent passport-size photograph", PdfKit.SMALL));
			box.setFixedHeight(96);
			box.setBorderColor(PdfKit.MUTED);
			box.setBorderWidth(0.8f);
			box.setHorizontalAlignment(org.openpdf.text.Element.ALIGN_CENTER);
			box.setVerticalAlignment(org.openpdf.text.Element.ALIGN_MIDDLE);
			box.setPadding(8);
			outer.addCell(box);
		}
		outer.setSpacingAfter(4);
		PdfKit.add(doc, outer);
	}

	private String text(String kind, Long classId) {
		return jdbc.queryForList("select body from admission_content where kind = ? and coalesce(class_id, 0) = ?",
				String.class, kind, classId == null ? 0 : classId).stream().findFirst().orElse(null);
	}
}
