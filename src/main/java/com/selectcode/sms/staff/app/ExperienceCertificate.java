package com.selectcode.sms.staff.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.pdf.CertificatePdf;
import com.selectcode.sms.shared.pdf.CertificateRegister;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.staff.StaffService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Experience certificate for current or former staff (FR 6.7). */
@Service
public class ExperienceCertificate {

	private final StaffService staff;

	private final CertificateRegister register;

	private final LetterheadSource letterhead;

	private final AuditService audit;

	private final Clock clock;

	ExperienceCertificate(StaffService staff, CertificateRegister register, LetterheadSource letterhead,
			AuditService audit, Clock clock) {
		this.staff = staff;
		this.register = register;
		this.letterhead = letterhead;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public byte[] issue(long staffId, String remarks) {
		var view = staff.get(staffId);
		var d = view.details();
		boolean female = "FEMALE".equals(d.gender());
		String relation = d.fatherOrHusbandName() == null ? ""
				: (female ? ", daughter/wife of " : ", son of ") + d.fatherOrHusbandName();
		String to = view.leavingDate() == null ? "to date" : "to " + PdfKit.date(view.leavingDate());
		String pronoun = female ? "She" : "He";
		String text = "This is to certify that %s%s, Employee No. %s, %s in this school as %s from %s %s."
			.formatted(d.name(), relation, view.employeeNo(), view.leavingDate() == null ? "is working" : "worked",
					d.designation(), PdfKit.date(d.joiningDate()), to);
		String conduct = "%s carried out the duties sincerely and %s conduct was satisfactory. We wish %s success."
			.formatted(pronoun, female ? "her" : "his", female ? "her" : "him");
		List<String> paragraphs = remarks == null || remarks.isBlank() ? List.of(text, conduct)
				: List.of(text, conduct, remarks.trim());
		var issued = register.issue(CertificateRegister.CertificateType.EXPERIENCE, null, staffId,
				Map.of("paragraphs", paragraphs));
		audit.record("CERTIFICATE_ISSUED", "staff", staffId, null,
				Map.of("type", "EXPERIENCE", "serialNo", issued.serialNo()));
		LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Karachi")));
		return CertificatePdf.render(letterhead.current(), new CertificatePdf.CertificateContent("EXPERIENCE CERTIFICATE",
				issued.serialNo(), today, paragraphs, List.of("Principal")));
	}
}
