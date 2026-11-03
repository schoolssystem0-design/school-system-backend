package com.selectcode.sms.shared.messaging;

/**
 * How many SMS parts a text costs (QA-COM-002). GSM-7: 160 characters, or 153 per part when split; a few symbols count
 * twice. Anything outside GSM-7 (such as curly quotes) makes the whole text UCS-2: 70, or 67 per part.
 */
public final class SmsSegments {

	private static final String GSM = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
			+ "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

	private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

	private SmsSegments() {
	}

	public record Count(String encoding, int characters, int segments) {
	}

	public static Count count(String text) {
		int gsmLength = 0;
		boolean gsm = true;
		for (int i = 0; i < text.length() && gsm; i++) {
			char c = text.charAt(i);
			if (GSM.indexOf(c) >= 0) {
				gsmLength++;
			}
			else if (GSM_EXTENDED.indexOf(c) >= 0) {
				gsmLength += 2;
			}
			else {
				gsm = false;
			}
		}
		if (gsm) {
			return new Count("GSM-7", gsmLength, gsmLength == 0 ? 0 : gsmLength <= 160 ? 1 : (gsmLength + 152) / 153);
		}
		int length = text.length();
		return new Count("UCS-2", length, length <= 70 ? 1 : (length + 66) / 67);
	}
}
