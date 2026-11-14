package com.selectcode.sms.admissions.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
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

	AdmissionDocuments(JdbcTemplate jdbc, AcademicStructure structure, AdmissionsService admissions,
			LetterheadSource letterheads, AuditService audit) {
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

	/** Admission form with the school heading, pre-filled from an enquiry if given, and the terms. */
	@Transactional(readOnly = true)
	public byte[] form(Long enquiryId, Long classId) {
		var lh = letterheads.current();
		Optional<AdmissionsService.EnquiryView> enquiry = enquiryId == null ? Optional.empty()
				: Optional.of(admissions.get(enquiryId));
		Long cls = classId != null ? classId : enquiry.map(e -> e.details().classAppliedId()).orElse(null);
		String terms = text("TERMS", null);
		String syllabus = cls == null ? null : text("SYLLABUS", cls);
		String className = cls == null ? "" : structure.findClass(cls).map(AcademicStructure.ClassRef::name).orElse("");
		return PdfKit.a4(doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "ADMISSION FORM");
			var d = enquiry.map(AdmissionsService.EnquiryView::details).orElse(null);
			List<String[]> rows = new ArrayList<>();
			rows.add(new String[] { "Registration No.", enquiry.map(AdmissionsService.EnquiryView::registrationNo).orElse("") });
			rows.add(new String[] { "Class applied for", className });
			rows.add(new String[] { "Child's name", d == null ? "" : d.childName() });
			rows.add(new String[] { "Gender", d == null || d.gender() == null ? "" : ("FEMALE".equals(d.gender()) ? "Girl" : "Boy") });
			rows.add(new String[] { "Date of birth", d == null || d.dob() == null ? "" : PdfKit.date(d.dob()) });
			rows.add(new String[] { "Place of birth", "" });
			rows.add(new String[] { "B-Form No.", "" });
			rows.add(new String[] { "Religion / Nationality", "" });
			rows.add(new String[] { "Previous school", d == null ? "" : PdfKit.nz(d.previousSchool()) });
			rows.add(new String[] { "Father / guardian name", d == null ? "" : PdfKit.nz(d.parentName()) });
			rows.add(new String[] { "Father CNIC", d == null ? "" : PdfKit.nz(d.parentCnic()) });
			rows.add(new String[] { "Father profession", "" });
			rows.add(new String[] { "Mother name", "" });
			rows.add(new String[] { "Phone numbers", d == null ? "" : PdfKit.nz(d.parentPhone()) });
			rows.add(new String[] { "Address", d == null ? "" : PdfKit.nz(d.address()) });
			PdfKit.details(doc, rows);
			if (syllabus != null) {
				PdfKit.paragraph(doc, "Entry test syllabus (" + className + "): " + syllabus);
			}
			if (terms != null) {
				PdfKit.paragraph(doc, "Terms and conditions: " + terms);
			}
			PdfKit.paragraph(doc, "I confirm that the information above is correct and accept the school's terms.");
			PdfKit.line(doc, "Parent / guardian signature: __________________", "Date: ____________");
			PdfKit.signatures(doc, lh, List.of("Principal"), false);
		});
	}

	private String text(String kind, Long classId) {
		return jdbc.queryForList("select body from admission_content where kind = ? and coalesce(class_id, 0) = ?",
				String.class, kind, classId == null ? 0 : classId).stream().findFirst().orElse(null);
	}
}
