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

/** A section of a class, e.g. 8-A (FR 9.3). The class teacher is assigned in Phase 2 with staff records. */
@Entity
@Table(name = "section")
public class Section extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "class_id", nullable = false)
	private Long classId;

	@Column(nullable = false, length = 30)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ActiveStatus status = ActiveStatus.ACTIVE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Section() {
	}

	public Section(long classId, String name) {
		this.classId = classId;
		this.name = name;
	}

	public void rename(String name, Instant now) {
		this.name = name;
		updatedAt = now;
	}

	public void setStatus(ActiveStatus status, Instant now) {
		this.status = status;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getClassId() {
		return classId;
	}

	public String getName() {
		return name;
	}

	public ActiveStatus getStatus() {
		return status;
	}

	public long getVersion() {
		return version;
	}
}
