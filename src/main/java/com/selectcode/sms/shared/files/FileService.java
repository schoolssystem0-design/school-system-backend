package com.selectcode.sms.shared.files;

import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploads and downloads with the checks from SECURITY.md §4: permission by purpose, size limit, type detected from
 * the file's bytes (the browser's content type and file name are never trusted), random storage keys under the
 * school's prefix.
 */
@Service
public class FileService {

	public record FileInfo(long id, String purpose, String originalName, String contentType, long sizeBytes) {

		static FileInfo of(StoredFile file) {
			return new FileInfo(file.getId(), file.getPurpose().name(), file.getOriginalName(), file.getContentType(),
					file.getSizeBytes());
		}
	}

	public record Download(StoredFile file, Optional<URI> redirect, byte[] content) {
	}

	private final StoredFileRepository files;

	private final FileStorage storage;

	FileService(StoredFileRepository files, FileStorage storage) {
		this.files = files;
		this.storage = storage;
	}

	@Transactional
	public FileInfo upload(FilePurpose purpose, String originalName, byte[] content) {
		if (purpose.uploadPermissions.stream().noneMatch(CurrentActor::hasPermission)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You cannot upload this type of file");
		}
		if (content.length == 0) {
			throw ApiException.businessRule("FILE_EMPTY", "The file is empty");
		}
		if (content.length > purpose.kind.maxBytes) {
			throw ApiException.businessRule("FILE_TOO_LARGE",
					"The file is larger than " + purpose.kind.maxBytes / (1024 * 1024) + " MB");
		}
		String type = detectType(content);
		if (type == null || !purpose.kind.allowedTypes.contains(type)) {
			throw ApiException.businessRule("FILE_TYPE_NOT_ALLOWED", "This file type is not allowed here");
		}
		long schoolId = TenantContext.requireSchoolId();
		String key = "schools/%d/%s/%s.%s".formatted(schoolId, purpose.name().toLowerCase(), UUID.randomUUID(),
				extension(type));
		storage.put(key, content, type);
		ActorPrincipal actor = CurrentActor.require();
		StoredFile saved = files.save(new StoredFile(key, purpose, safeName(originalName, type), type, content.length,
				sha256(content), actor.actorType().name(), actor.actorId()));
		return FileInfo.of(saved);
	}

	@Transactional(readOnly = true)
	public Optional<FileInfo> find(long id) {
		return files.findById(id).map(FileInfo::of);
	}

	/** File bytes for server-side use (PDF images). Row-level security limits this to the current school. */
	@Transactional(readOnly = true)
	public Optional<byte[]> content(Long id) {
		if (id == null) {
			return Optional.empty();
		}
		return files.findById(id).map(f -> storage.get(f.getStorageKey()));
	}

	/** Row-level security limits this to the current school's files. */
	@Transactional(readOnly = true)
	public Download download(long id) {
		StoredFile file = files.findById(id).orElseThrow(() -> ApiException.notFound("File"));
		var allowed = file.getPurpose().viewPermissions;
		if (!allowed.isEmpty() && allowed.stream().noneMatch(CurrentActor::hasPermission)) {
			throw ApiException.notFound("File"); // private file: do not reveal it exists
		}
		Optional<URI> url = storage.presignedGetUrl(file.getStorageKey(), Duration.ofMinutes(5),
				file.getOriginalName());
		return new Download(file, url, url.isPresent() ? null : storage.get(file.getStorageKey()));
	}

	/** Detects the real type from magic bytes. */
	static String detectType(byte[] b) {
		if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
			return "image/png";
		}
		if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
			return "image/jpeg";
		}
		if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
				&& b[10] == 'B' && b[11] == 'P') {
			return "image/webp";
		}
		if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
			return "application/pdf";
		}
		return null;
	}

	private static String extension(String type) {
		return switch (type) {
			case "image/png" -> "png";
			case "image/jpeg" -> "jpg";
			case "image/webp" -> "webp";
			case "application/pdf" -> "pdf";
			default -> "bin";
		};
	}

	private static String safeName(String name, String type) {
		String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		if (base.isEmpty()) {
			base = "file." + extension(type);
		}
		return base.length() > 120 ? base.substring(base.length() - 120) : base;
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
