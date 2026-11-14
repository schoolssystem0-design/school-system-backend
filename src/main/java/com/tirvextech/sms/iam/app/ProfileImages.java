package com.selectcode.sms.iam.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Pictures shown with the signed-in user: their own photo and their school's logo. */
@Component
public class ProfileImages {

	private final JdbcTemplate jdbc;

	ProfileImages(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Long userPhoto(long userId) {
		return jdbc.queryForList("select photo_file_id from app_user where id = ?", Long.class, userId)
			.stream()
			.filter(java.util.Objects::nonNull)
			.findFirst()
			.orElse(null);
	}

	public Long schoolLogo(long schoolId) {
		return jdbc.queryForList("select logo_file_id from school_profile where school_id = ?", Long.class, schoolId)
			.stream()
			.filter(java.util.Objects::nonNull)
			.findFirst()
			.orElse(null);
	}
}
