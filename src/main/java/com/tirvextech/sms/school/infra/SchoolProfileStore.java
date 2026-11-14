package com.selectcode.sms.school.infra;

import com.selectcode.sms.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The school profile row ({@code school_profile}, primary key = school_id). Plain JDBC: the table's key is the tenant
 * itself, which does not fit the {@code @TenantId} base entity. Row-level security applies.
 */
@Repository
public class SchoolProfileStore {

	public record Signature(String role, String name, String title, Long fileId) {
	}

	public record Profile(String name, String campusName, String address, String phonePrimary, String phoneSecondary,
			String email, String headName, String headTitle, Long logoFileId, Long letterheadFileId, Long stampFileId,
			List<Signature> signatures, long version) {
	}

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	SchoolProfileStore(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public void create(String name) {
		jdbc.update("insert into school_profile (school_id, name) values (?, ?) on conflict do nothing",
				TenantContext.requireSchoolId(), name);
	}

	public Optional<Profile> find() {
		List<Profile> rows = jdbc.query("""
				select name, campus_name, address, phone_primary, phone_secondary, email, head_name, head_title,
				       logo_file_id, letterhead_file_id, stamp_file_id, signatures::text, version
				from school_profile where school_id = ?""", (rs, i) -> new Profile(rs.getString(1), rs.getString(2),
				rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
				(Long) rs.getObject(9), (Long) rs.getObject(10), (Long) rs.getObject(11), signatures(rs.getString(12)),
				rs.getLong(13)), TenantContext.requireSchoolId());
		return rows.stream().findFirst();
	}

	/** @return false if the version did not match (someone else saved first) */
	public boolean update(Profile p) {
		int updated = jdbc.update("""
				update school_profile set name = ?, campus_name = ?, address = ?, phone_primary = ?, phone_secondary = ?,
				       email = ?, head_name = ?, head_title = ?, logo_file_id = ?, letterhead_file_id = ?,
				       stamp_file_id = ?, signatures = ?::jsonb, updated_at = now(), version = version + 1
				where school_id = ? and version = ?""", p.name(), p.campusName(), p.address(), p.phonePrimary(),
				p.phoneSecondary(), p.email(), p.headName(), p.headTitle(), p.logoFileId(), p.letterheadFileId(),
				p.stampFileId(), json.writeValueAsString(p.signatures()), TenantContext.requireSchoolId(), p.version());
		return updated == 1;
	}

	private List<Signature> signatures(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		return json.readValue(text, new TypeReference<List<Signature>>() {
		});
	}
}
