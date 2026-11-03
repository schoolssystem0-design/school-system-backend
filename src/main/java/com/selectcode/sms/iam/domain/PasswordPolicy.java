package com.selectcode.sms.iam.domain;

import com.selectcode.sms.shared.web.ApiException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * Password rules (SECURITY.md §1): at least 10 characters, not a common password, not based on the username. No
 * forced character classes; long passphrases are encouraged.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 10;

	public static final int MAX_LENGTH = 128;

	private static final Set<String> COMMON = Set.of("password", "password1", "password12", "password123",
			"password1234", "1234567890", "12345678910", "0123456789", "qwertyuiop", "qwerty1234", "1q2w3e4r5t",
			"iloveyou123", "admin12345", "administrator", "welcome123", "letmein123", "abc1234567", "pakistan123",
			"pakistan786", "school1234", "school12345", "teacher123", "principal1", "changeme123", "1111111111",
			"0000000000", "asdfghjkl1", "zxcvbnm123", "computer123", "football123", "monkey12345", "superman123",
			"sunshine123", "princess123", "dragon12345", "baseball123", "trustno1234", "passw0rd123", "p@ssw0rd123",
			"p@ssword123", "pass@12345", "admin@12345", "admin@123", "test123456", "testtest12");

	private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

	private static final SecureRandom RANDOM = new SecureRandom();

	private PasswordPolicy() {
	}

	public static void check(String password, String username) {
		if (password == null || password.length() < MIN_LENGTH) {
			throw ApiException.businessRule("PASSWORD_TOO_SHORT",
					"Use at least " + MIN_LENGTH + " characters (a short phrase works well)");
		}
		if (password.length() > MAX_LENGTH) {
			throw ApiException.businessRule("PASSWORD_TOO_LONG", "Use at most " + MAX_LENGTH + " characters");
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (COMMON.contains(lower) || password.chars().distinct().count() <= 2) {
			throw ApiException.businessRule("PASSWORD_TOO_COMMON", "This password is too easy to guess");
		}
		if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
			throw ApiException.businessRule("PASSWORD_CONTAINS_USERNAME", "The password must not contain the username");
		}
	}

	/** A random temporary password (12 characters, no look-alike characters), to be changed at next sign-in. */
	public static String temporary() {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			out.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
		}
		return out.toString();
	}
}
