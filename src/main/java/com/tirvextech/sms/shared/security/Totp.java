package com.selectcode.sms.shared.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords (HMAC-SHA1, 30-second steps, 6 digits), compatible with Google
 * Authenticator, Microsoft Authenticator, 1Password etc. Accepts one step of clock drift either way.
 */
public final class Totp {

	private static final int DIGITS = 6;

	private static final long STEP_SECONDS = 30;

	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	/** A new random 160-bit secret, Base32-encoded. */
	public static String newSecret() {
		byte[] secret = new byte[20];
		RANDOM.nextBytes(secret);
		return Base32.encode(secret);
	}

	public static String otpauthUri(String issuer, String account, String base32Secret) {
		String label = urlEncode(issuer) + ":" + urlEncode(account);
		return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + urlEncode(issuer)
				+ "&algorithm=SHA1&digits=6&period=30";
	}

	public static boolean verify(String base32Secret, String code, Instant now) {
		if (base32Secret == null || code == null || !code.trim().matches("\\d{6}")) {
			return false;
		}
		byte[] key = Base32.decode(base32Secret);
		long step = now.getEpochSecond() / STEP_SECONDS;
		String expected = code.trim();
		boolean match = false;
		for (long offset = -1; offset <= 1; offset++) {
			// Compare all candidates to keep timing independent of which step matched.
			match |= java.security.MessageDigest.isEqual(generate(key, step + offset).getBytes(), expected.getBytes());
		}
		return match;
	}

	/** The current code for a secret (used by tests and local development tooling). */
	public static String codeAt(String base32Secret, Instant at) {
		return generate(Base32.decode(base32Secret), at.getEpochSecond() / STEP_SECONDS);
	}

	static String generate(byte[] key, long counter) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
					| ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
			int otp = binary % 1_000_000;
			return String.format("%0" + DIGITS + "d", otp);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** RFC 4648 Base32 without padding. */
	static final class Base32 {

		private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

		private Base32() {
		}

		static String encode(byte[] data) {
			StringBuilder out = new StringBuilder();
			int buffer = 0;
			int bits = 0;
			for (byte b : data) {
				buffer = (buffer << 8) | (b & 0xff);
				bits += 8;
				while (bits >= 5) {
					out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
					bits -= 5;
				}
			}
			if (bits > 0) {
				out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
			}
			return out.toString();
		}

		static byte[] decode(String text) {
			String clean = text.replace("=", "").replace(" ", "").toUpperCase();
			ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
			int buffer = 0;
			int bits = 0;
			for (char c : clean.toCharArray()) {
				int value = ALPHABET.indexOf(c);
				if (value < 0) {
					throw new IllegalArgumentException("Invalid Base32 character");
				}
				buffer = (buffer << 5) | value;
				bits += 5;
				if (bits >= 8) {
					out.put((byte) ((buffer >> (bits - 8)) & 0xff));
					bits -= 8;
				}
			}
			return java.util.Arrays.copyOf(out.array(), out.position());
		}
	}
}
