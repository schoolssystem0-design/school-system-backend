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

/** A class (grade), e.g. Play Group, Class 1 … Class 10 (FR 9.2). */
@Entity
@Table(name = "school_class")
public class SchoolClass extends TenantScopedEntity {

	public enum GradingMode {
		MARKS, GRADES
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(name = "short_name", nullable = false, length = 20)
	private String shortName;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Enumerated(EnumType.STRING)
	@Column(name = "grading_mode", nullable = false, length = 10)
	private GradingMode gradingMode = GradingMode.MARKS;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ActiveStatus status = ActiveStatus.ACTIVE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected SchoolClass() {
	}

	public SchoolClass(String name, String shortName, int displayOrder, GradingMode gradingMode) {
		this.name = name;
		this.shortName = shortName;
		this.displayOrder = displayOrder;
		this.gradingMode = gradingMode;
	}

	public void change(String name, String shortName, GradingMode gradingMode, Instant now) {
		this.name = name;
		this.shortName = shortName;
		this.gradingMode = gradingMode;
		updatedAt = now;
	}

	public void moveTo(int displayOrder, Instant now) {
		this.displayOrder = displayOrder;
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

	public int getDisplayOrder() {
		return displayOrder;
	}

	public GradingMode getGradingMode() {
		return gradingMode;
	}

	public ActiveStatus getStatus() {
		return status;
	}

	public long getVersion() {
		return version;
	}
}
