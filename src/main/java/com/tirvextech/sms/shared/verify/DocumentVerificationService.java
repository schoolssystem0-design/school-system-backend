package com.tirvextech.sms.shared.verify;

import com.tirvextech.sms.shared.pdf.PdfKit;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers printed documents so anyone can scan the QR code and confirm they were issued by the school (anti-forgery).
 */
@Service
public class DocumentVerificationService {

	public enum DocKind {
		CERTIFICATE, FEE_RECEIPT, REPORT_CARD
	}

	public record Registered(String token, String verifyUrl) {
	}

	public record VerifyResult(boolean genuine, String schoolName, String documentTitle, String issuedOn, String message,
			String subjectLine) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

	private final JdbcTemplate jdbc;

	private final Environment env;

	DocumentVerificationService(JdbcTemplate jdbc, Environment env) {
		this.jdbc = jdbc;
		this.env = env;
	}

	@Transactional
	public Registered register(DocKind kind, String refType, long refId, String documentTitle, String subjectLine,
			LocalDate issuedOn) {
		long schoolId = TenantContext.requireSchoolId();
		String token = newToken();
		jdbc.update("""
				insert into document_verification (token, school_id, doc_kind, ref_type, ref_id, document_title, subject_line, issued_on)
				values (?, ?, ?, ?, ?, ?, ?, ?)""", token, schoolId, kind.name(), refType, refId, documentTitle.trim(),
				subjectLine.trim(), java.sql.Date.valueOf(issuedOn));
		return new Registered(token, publicUrl(token));
	}

	@Transactional(readOnly = true)
	public Optional<VerifyResult> verify(String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{16,32}")) {
			return Optional.empty();
		}
		return jdbc
			.query("""
					select coalesce(nullif(trim(sp.name), ''), s.code), dv.document_title, dv.subject_line, dv.issued_on
					from document_verification dv
					join school s on s.id = dv.school_id
					left join school_profile sp on sp.school_id = s.id
					where dv.token = ?""",
					rs -> {
						if (!rs.next()) {
							return Optional.<VerifyResult>empty();
						}
						String school = rs.getString(1);
						String title = rs.getString(2);
						String subject = rs.getString(3);
						LocalDate issued = rs.getDate(4).toLocalDate();
						String on = LONG_DATE.format(issued);
						String message = "Genuine: issued by %s on %s.".formatted(school, on);
						return Optional.of(new VerifyResult(true, school, title, PdfKit.date(issued), message, subject));
					},
					token);
	}

	public String publicUrl(String token) {
		String base = env.getProperty("app.web-base-url", "http://localhost:3000").replaceAll("/+$", "");
		return base + "/verify/" + token;
	}

	private static String newToken() {
		byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}