package com.tirvextech.sms.shared.messaging.smsgate;

import com.tirvextech.sms.shared.web.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets stored in the database (SMS Gate password and webhook signing key) with AES-256-GCM. The key is
 * APP_SECRETS_KEY (any long random string). Local development and tests use a fixed key; elsewhere, saving a secret
 * without the key is refused, so a secret is never stored in plain text.
 */
@Component
public class SecretBox {

	private static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;

	private final SecureRandom random = new SecureRandom();

	private final SecretKeySpec key;

	SecretBox(@Value("${app.secrets.key:}") String configured, Environment environment) {
		String material = configured;
		if (material == null || material.isBlank()) {
			material = environment.acceptsProfiles(Profiles.of("local", "test")) ? "local-development-only-secret-key"
					: null;
		}
		this.key = material == null ? null : new SecretKeySpec(sha256(material), "AES");
	}

	public boolean available() {
		return key != null;
	}

	public String encrypt(String plain) {
		if (key == null) {
			throw ApiException.businessRule("SECRETS_KEY_MISSING",
					"The server has no APP_SECRETS_KEY, so passwords cannot be stored safely. Ask the system owner to set it.");
		}
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder()
				.encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	/** @return the secret, or null when it cannot be read (no key, or the key changed) */
	public String decrypt(String stored) {
		if (key == null || stored == null || !stored.startsWith(PREFIX)) {
			return null;
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
			return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			return null;
		}
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
