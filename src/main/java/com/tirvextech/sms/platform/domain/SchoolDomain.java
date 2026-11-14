package com.selectcode.sms.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A public website host name mapped to a school (FR 16, P-6). Platform-level table. */
@Entity
@Table(name = "school_domain")
public class SchoolDomain {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "school_id", nullable = false)
	private Long schoolId;

	@Column(nullable = false, length = 253)
	private String host;

	@Column(nullable = false)
	private boolean verified;

	@Column(name = "is_primary", nullable = false)
	private boolean primary;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected SchoolDomain() {
	}

	public SchoolDomain(long schoolId, String host, boolean primary) {
		this.schoolId = schoolId;
		this.host = host;
		this.primary = primary;
	}

	public Long getId() {
		return id;
	}

	public Long getSchoolId() {
		return schoolId;
	}

	public String getHost() {
		return host;
	}

	public boolean isVerified() {
		return verified;
	}

	public boolean isPrimary() {
		return primary;
	}
}
