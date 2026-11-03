package com.selectcode.sms.academics.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** A subject in the school's master list (FR 9.4); per-class marks are in {@link ClassSubject}. */
@Entity
@Table(name = "subject")
public class Subject extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(name = "short_name", nullable = false, length = 20)
	private String shortName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ActiveStatus status = ActiveStatus.ACTIVE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Subject() {
	}

	public Subject(String name, String shortName) {
		this.name = name;
		this.shortName = shortName;
	}

	public void change(String name, String shortName, Instant now) {
		this.name = name;
		this.shortName = shortName;
		updatedAt = now;
	}

	public void setStatus(ActiveStatus status, Instant now) {
		this.status = status;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getShortName() {
		return shortName;
	}

	public ActiveStatus getStatus() {
		return status;
	}

	public long getVersion() {
		return version;
	}
}
