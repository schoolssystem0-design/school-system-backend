package com.selectcode.sms.school.domain;

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
import java.time.LocalDate;

/** An academic year (session), e.g. 2026-2027 (FR 1.2, 9.1). Exactly one is CURRENT per school. */
@Entity
@Table(name = "academic_year")
public class AcademicYear extends TenantScopedEntity {

	public enum Status {
		UPCOMING, CURRENT, CLOSED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String name;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status = Status.UPCOMING;

	@Column(name = "opened_at")
	private Instant openedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected AcademicYear() {
	}

	public AcademicYear(String name, LocalDate startDate, LocalDate endDate) {
		this.name = name;
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public void makeCurrent(Instant now) {
		status = Status.CURRENT;
		openedAt = now;
		updatedAt = now;
	}

	public void close(Instant now) {
		status = Status.CLOSED;
		closedAt = now;
		updatedAt = now;
	}

	public void change(String name, LocalDate startDate, LocalDate endDate, Instant now) {
		this.name = name;
		this.startDate = startDate;
		this.endDate = endDate;
		updatedAt = now;
	}

	public boolean contains(LocalDate date) {
		return !date.isBefore(startDate) && !date.isAfter(endDate);
	}

	public boolean overlaps(LocalDate from, LocalDate to) {
		return !from.isAfter(endDate) && !to.isBefore(startDate);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getOpenedAt() {
		return openedAt;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public long getVersion() {
		return version;
	}
}
