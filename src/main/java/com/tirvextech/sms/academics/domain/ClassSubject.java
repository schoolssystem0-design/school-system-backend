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
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A subject taught in a class in one academic year, with maximum and passing marks (FR 9.4, 14.2). Kept per year so
 * changing next year's marks never rewrites past results.
 */
@Entity
@Table(name = "class_subject")
public class ClassSubject extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "academic_year_id", nullable = false)
	private Long academicYearId;

	@Column(name = "class_id", nullable = false)
	private Long classId;

	@Column(name = "subject_id", nullable = false)
	private Long subjectId;

	@Column(name = "max_marks", nullable = false, precision = 6, scale = 2)
	private BigDecimal maxMarks;

	@Column(name = "passing_marks", precision = 6, scale = 2)
	private BigDecimal passingMarks;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ActiveStatus status = ActiveStatus.ACTIVE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected ClassSubject() {
	}

	public ClassSubject(long academicYearId, long classId, long subjectId, BigDecimal maxMarks,
			BigDecimal passingMarks, int displayOrder) {
		this.academicYearId = academicYearId;
		this.classId = classId;
		this.subjectId = subjectId;
		this.maxMarks = maxMarks;
		this.passingMarks = passingMarks;
		this.displayOrder = displayOrder;
	}

	public void change(BigDecimal maxMarks, BigDecimal passingMarks, int displayOrder, Instant now) {
		this.maxMarks = maxMarks;
		this.passingMarks = passingMarks;
		this.displayOrder = displayOrder;
		this.status = ActiveStatus.ACTIVE;
		updatedAt = now;
	}

	public void setStatus(ActiveStatus status, Instant now) {
		this.status = status;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getAcademicYearId() {
		return academicYearId;
	}

	public Long getClassId() {
		return classId;
	}

	public Long getSubjectId() {
		return subjectId;
	}

	public BigDecimal getMaxMarks() {
		return maxMarks;
	}

	public BigDecimal getPassingMarks() {
		return passingMarks;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public ActiveStatus getStatus() {
		return status;
	}
}
