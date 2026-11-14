package com.selectcode.sms.shared.validation;

import com.selectcode.sms.shared.web.ApiException;
import java.util.regex.Pattern;

/**
 * Normalises Pakistani phone numbers and CNIC / B-Form numbers so they can be matched (duplicate detection, family
 * search) regardless of how they were typed.
 */
public final class PakFormats {

	private static final Pattern CNIC = Pattern.compile("\\d{13}");

	private PakFormats() {
	}

	/**
	 * "0300-1234567", "+92 300 1234567", "923001234567" → "03001234567". Landlines keep their digits (e.g.
	 * "0421234567"). Returns null for blank input.
	 */
	public static String phone(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		if (digits.startsWith("0092")) {
			digits = "0" + digits.substring(4);
		}
		else if (digits.startsWith("92") && digits.length() == 12) {
			digits = "0" + digits.substring(2);
		}
		if (digits.length() < 9 || digits.length() > 12 || !digits.startsWith("0")) {
			throw ApiException.businessRule("PHONE_INVALID", field + ": enter a Pakistani number like 0300-1234567");
		}
		return digits;
	}

	/** "35202-1234567-1" or 13 digits → "35202-1234567-1". Returns null for blank input. */
	public static String cnic(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		if (!CNIC.matcher(digits).matches()) {
			throw ApiException.businessRule("CNIC_INVALID", field + ": enter 13 digits, e.g. 35202-1234567-1");
		}
		return digits.substring(0, 5) + "-" + digits.substring(5, 12) + "-" + digits.substring(12);
	}

	/** Lenient variants for searching: never throw, return null if the input cannot be a phone/CNIC. */
	public static String phoneOrNull(String raw) {
		try {
			return phone(raw, "phone");
		}
		catch (ApiException ex) {
			return null;
		}
	}

	public static String cnicOrNull(String raw) {
		try {
			return cnic(raw, "cnic");
		}
		catch (ApiException ex) {
			return null;
		}
	}

	public static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
