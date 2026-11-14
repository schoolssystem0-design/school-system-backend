package com.selectcode.sms.shared.pdf;

import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues certificate serial numbers and keeps a register of what was printed (FR 5.8, 6.7). The register is
 * insert-only, so any certificate can be verified later by its serial number.
 */
@Component
public class CertificateRegister {

	public enum CertificateType {

		LEAVING("LC"), DATE_OF_BIRTH("DOB"), CHARACTER("CC"), APPRECIATION("APP"), PROVISIONAL("PRV"), HOPE("HOP"),
		EXPERIENCE("EXP");

		public final String prefix;

		CertificateType(String prefix) {
			this.prefix = prefix;
		}
	}

	public record IssuedCertificate(long id, String serialNo) {
	}

	public record CertificateEntry(long id, String type, String serialNo, String issuedBy, java.time.Instant issuedAt,
			String content) {
	}

	private final NumberSequences sequences;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	CertificateRegister(NumberSequences sequences, JdbcTemplate jdbc, JsonMapper json) {
		this.sequences = sequences;
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public IssuedCertificate issue(CertificateType type, Long studentId, Long staffId, Map<String, Object> content) {
		String serial = NumberSequences.format(type.prefix, sequences.next("certificate." + type.name()), 5);
		Long id = jdbc.queryForObject("""
				insert into certificate_issue (school_id, cert_type, serial_no, student_id, staff_id, content, issued_by_name)
				values (?, ?, ?, ?, ?, ?::jsonb, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
				type.name(), serial, studentId, staffId, json.writeValueAsString(content),
				CurrentActor.get().map(a -> a.displayName()).orElse(null));
		return new IssuedCertificate(id, serial);
	}

	@Transactional(readOnly = true)
	public List<CertificateEntry> forStudent(long studentId) {
		return jdbc.query("""
				select id, cert_type, serial_no, issued_by_name, issued_at, content::text from certificate_issue
				where student_id = ? order by issued_at desc""", (rs, i) -> new CertificateEntry(rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)), studentId);
	}

	@Transactional(readOnly = true)
	public List<CertificateEntry> forStaff(long staffId) {
		return jdbc.query("""
				select id, cert_type, serial_no, issued_by_name, issued_at, content::text from certificate_issue
				where staff_id = ? order by issued_at desc""", (rs, i) -> new CertificateEntry(rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)), staffId);
	}
}
