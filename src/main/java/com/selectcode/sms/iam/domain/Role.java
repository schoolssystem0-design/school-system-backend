package com.selectcode.sms.iam.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** A named set of permissions within one school. Permissions are stored in {@code role_permission}. */
@Entity
@Table(name = "role")
public class Role extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 300)
	private String description;

	@Column(name = "system_role", nullable = false)
	private boolean systemRole;

	@Column(name = "template_version", nullable = false)
	private int templateVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Role() {
	}

	public Role(String code, String name, String description, boolean systemRole, int templateVersion) {
		this.code = code;
		this.name = name;
		this.description = description;
		this.systemRole = systemRole;
		this.templateVersion = templateVersion;
	}

	/** The administrator role always has every permission and cannot be edited (prevents lock-out). */
	public boolean isAdministrator() {
		return RoleTemplates.ADMIN.equals(code);
	}

	public void rename(String name, String description, Instant now) {
		this.name = name;
		this.description = description;
		updatedAt = now;
	}

	public void upgradedTo(int templateVersion, Instant now) {
		this.templateVersion = templateVersion;
		updatedAt = now;
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

	public String getDescription() {
		return description;
	}

	public boolean isSystemRole() {
		return systemRole;
	}

	public int getTemplateVersion() {
		return templateVersion;
	}

	public long getVersion() {
		return version;
	}
}
