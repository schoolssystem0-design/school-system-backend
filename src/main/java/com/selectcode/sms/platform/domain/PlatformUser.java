package com.selectcode.sms.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/** The super admin (system owner). Platform-level: not tied to any school. TOTP is mandatory. */
@Entity
@Table(name = "platform_user")
public class PlatformUser {

	public static final int MAX_FAILED_ATTEMPTS = 5;

	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "password_hash", nullable = false, length = 200)
	private String passwordHash;

	@Column(name = "totp_secret", length = 200)
	private String totpSecret;

	@Column(nullable = false, length = 20)
	private String status = "ACTIVE";

	@Column(name = "failed_attempts", nullable = false)
	private int failedAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected PlatformUser() {
	}

	public PlatformUser(String email, String name, String passwordHash) {
		this.email = email;
		this.name = name;
		this.passwordHash = passwordHash;
	}

	public boolean isActive() {
		return "ACTIVE".equals(status);
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void recordFailure(Instant now) {
		failedAttempts++;
		if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
			lockedUntil = now.plus(LOCK_DURATION);
			failedAttempts = 0;
		}
		updatedAt = now;
	}

	public void recordSuccess(Instant now) {
		failedAttempts = 0;
		lockedUntil = null;
		lastLoginAt = now;
		updatedAt = now;
	}

	public void enrollTotp(String secret, Instant now) {
		totpSecret = secret;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getName() {
		return name;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getTotpSecret() {
		return totpSecret;
	}
}
