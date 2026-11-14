package com.selectcode.sms.platform.domain;

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

/** A school (tenant) on the platform. Platform-level table: not tenant-scoped. */
@Entity
@Table(name = "school")
public class School {

	public enum Status {
		ACTIVE, SUSPENDED, OFFBOARDING
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status = Status.ACTIVE;

	@Column(name = "suspended_reason", length = 500)
	private String suspendedReason;

	@Column(nullable = false, length = 64)
	private String timezone = "Asia/Karachi";

	/** End of the free trial; null = full (paid) access. */
	@Column(name = "trial_ends_at")
	private Instant trialEndsAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected School() {
	}

	public void rename(String name, Instant now) {
		this.name = name;
		updatedAt = now;
	}

	public void suspend(String reason, Instant now) {
		status = Status.SUSPENDED;
		suspendedReason = reason;
		updatedAt = now;
	}

	public void reactivate(Instant now) {
		status = Status.ACTIVE;
		suspendedReason = null;
		updatedAt = now;
	}

	/** Full access from now on: the free trial is over (the school became a customer). */
	public void endTrial(Instant now) {
		trialEndsAt = null;
		updatedAt = now;
	}

	/** Moves the end of the trial; an ended trial starts again from now. */
	public void extendTrial(int days, Instant now) {
		Instant from = trialEndsAt == null || trialEndsAt.isBefore(now) ? now : trialEndsAt;
		trialEndsAt = from.plus(java.time.Duration.ofDays(days));
		updatedAt = now;
	}

	public Instant getTrialEndsAt() {
		return trialEndsAt;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public Status getStatus() {
		return status;
	}

	public String getSuspendedReason() {
		return suspendedReason;
	}

	public String getTimezone() {
		return timezone;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public long getVersion() {
		return version;
	}
}
