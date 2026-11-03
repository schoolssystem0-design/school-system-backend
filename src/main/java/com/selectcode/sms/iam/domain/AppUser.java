package com.selectcode.sms.iam.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import com.selectcode.sms.shared.security.DataScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/** A school staff user. Belongs to exactly one school. */
@Entity
@Table(name = "app_user")
public class AppUser extends TenantScopedEntity {

	public static final int MAX_FAILED_ATTEMPTS = 5;

	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	public enum Status {
		ACTIVE, INACTIVE, LOCKED
	}

	/** Which records a user may see (FR 2.5); enforced by later modules' queries. */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String username;

	@Column(name = "display_name", nullable = false, length = 200)
	private String displayName;

	@Column(name = "password_hash", nullable = false, length = 200)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status = Status.ACTIVE;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "failed_attempts", nullable = false)
	private int failedAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(length = 254)
	private String email;

	@Column(length = 30)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "data_scope", nullable = false, length = 30)
	private DataScope dataScope = DataScope.ALL;

	@Column(name = "is_main_admin", nullable = false)
	private boolean mainAdmin;

	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	/** The staff record this login belongs to (FR 6.2, 7.2), if any. */
	@Column(name = "staff_id")
	private Long staffId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected AppUser() {
	}

	public AppUser(String username, String displayName, String passwordHash) {
		this.username = username;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
	}

	public static AppUser create(String username, String displayName, String email, String phone,
			DataScope dataScope, String passwordHash, boolean mustChangePassword) {
		AppUser user = new AppUser(username, displayName, passwordHash);
		user.email = email;
		user.phone = phone;
		user.dataScope = dataScope;
		user.mustChangePassword = mustChangePassword;
		return user;
	}

	public boolean canSignIn(Instant now) {
		return status == Status.ACTIVE && !isLocked(now);
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void unlock(Instant now) {
		lockedUntil = null;
		failedAttempts = 0;
		updatedAt = now;
	}

	public void setPassword(String newHash, boolean mustChangeNext, Instant now) {
		passwordHash = newHash;
		mustChangePassword = mustChangeNext;
		passwordChangedAt = now;
		failedAttempts = 0;
		lockedUntil = null;
		updatedAt = now;
	}

	public void updateDetails(String displayName, String email, String phone, DataScope dataScope, Instant now) {
		this.displayName = displayName;
		this.email = email;
		this.phone = phone;
		this.dataScope = dataScope;
		updatedAt = now;
	}

	public void changeStatus(Status newStatus, Instant now) {
		status = newStatus;
		updatedAt = now;
	}

	public void linkStaff(Long staffId) {
		this.staffId = staffId;
	}

	public Long getStaffId() {
		return staffId;
	}

	public void markMainAdmin() {
		mainAdmin = true;
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Status getStatus() {
		return status;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public DataScope getDataScope() {
		return dataScope;
	}

	public boolean isMainAdmin() {
		return mainAdmin;
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
