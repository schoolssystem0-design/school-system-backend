package com.selectcode.sms.shared.files;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Metadata of a file kept in object storage. Insert-only. */
@Entity
@Table(name = "stored_file")
public class StoredFile extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "storage_key", nullable = false, length = 300)
	private String storageKey;

	@Column(nullable = false, length = 40)
	private String purpose;

	@Column(name = "original_name", nullable = false)
	private String originalName;

	@Column(name = "content_type", nullable = false, length = 100)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(nullable = false, length = 64)
	private String sha256;

	@Column(name = "created_by_type", nullable = false, length = 20)
	private String createdByType;

	@Column(name = "created_by_id")
	private Long createdById;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected StoredFile() {
	}

	StoredFile(String storageKey, FilePurpose purpose, String originalName, String contentType, long sizeBytes,
			String sha256, String createdByType, Long createdById) {
		this.storageKey = storageKey;
		this.purpose = purpose.name();
		this.originalName = originalName;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.createdByType = createdByType;
		this.createdById = createdById;
	}

	public Long getId() {
		return id;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public FilePurpose getPurpose() {
		return FilePurpose.valueOf(purpose);
	}

	public String getOriginalName() {
		return originalName;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}
}
