package com.tirvextech.sms.school.app;

import com.tirvextech.sms.shared.files.FileService;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A school's logo by school code, for the sign-in page (no one signed in yet). Only the logo is exposed; an unknown
 * code, an inactive school and a school without a logo all give nothing.
 */
@Service
public class PublicSchoolLogo {

	public record Logo(byte[] content, String contentType) {
	}

	private final JdbcTemplate jdbc;

	private final FileService files;

	PublicSchoolLogo(JdbcTemplate jdbc, FileService files) {
		this.jdbc = jdbc;
		this.files = files;
	}

	public Optional<Logo> find(String code) {
		String normalised = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
		if (!normalised.matches("[A-Z0-9][A-Z0-9-]{1,19}")) {
			return Optional.empty();
		}
		Optional<Long> schoolId = TenantContext.callAcrossAllSchools(() -> jdbc
			.queryForList("select id from school where code = ? and status = 'ACTIVE'", Long.class, normalised)
			.stream()
			.findFirst());
		return schoolId.flatMap(id -> TenantContext.callAsSchool(id, () -> {
			Long fileId = jdbc.queryForList("select logo_file_id from school_profile where school_id = ?", Long.class, id)
				.stream()
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
			if (fileId == null) {
				return Optional.<Logo>empty();
			}
			String type = files.find(fileId).map(FileService.FileInfo::contentType).orElse("image/png");
			return files.content(fileId).map(bytes -> new Logo(bytes, type));
		}));
	}
}
