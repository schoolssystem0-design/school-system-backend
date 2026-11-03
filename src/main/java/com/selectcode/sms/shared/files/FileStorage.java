package com.selectcode.sms.shared.files;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** Object storage for uploaded and generated files: Cloudflare R2 in staging/production, local disk in development. */
public interface FileStorage {

	void put(String key, byte[] content, String contentType);

	byte[] get(String key);

	/** A short-lived direct download URL, if the store supports it (R2); otherwise the API streams the file. */
	Optional<URI> presignedGetUrl(String key, Duration validity, String downloadName);
}
