package com.selectcode.sms.shared.security;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import com.selectcode.sms.shared.web.ApiException;

/**
 * Step-up verification state, kept in the server-side session: a pending one-time code (hashed) and, once verified,
 * the time until which sensitive actions are allowed.
 */
public final class StepUp {

	public static final Duration VALIDITY = Duration.ofMinutes(15);

	public static final Duration CODE_LIFETIME = Duration.ofMinutes(5);

	public static final int MAX_ATTEMPTS = 5;

	static final String VERIFIED_UNTIL = "sms.stepUp.verifiedUntil";

	static final String CHALLENGE = "sms.stepUp.challenge";

	private static final SecureRandom RANDOM = new SecureRandom();

	record Challenge(String codeHash, Instant expiresAt, int attempts) implements Serializable {
	}

	private StepUp() {
	}

	public static boolean isActive(HttpSession session, Instant now) {
		if (session == null) {
			return false;
		}
		Object until = session.getAttribute(VERIFIED_UNTIL);
		return until instanceof Instant instant && instant.isAfter(now);
	}

	public static void clear(HttpSession session) {
		if (session != null) {
			session.removeAttribute(VERIFIED_UNTIL);
			session.removeAttribute(CHALLENGE);
		}
	}

	public static Instant verifiedUntil(HttpSession session) {
		Object until = session == null ? null : session.getAttribute(VERIFIED_UNTIL);
		return until instanceof Instant instant ? instant : null;
	}

	/** Creates a new 6-digit code, stores its hash in the session and returns the plain code (to send to the user). */
	public static String newChallenge(HttpSession session, Instant now) {
		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		session.setAttribute(CHALLENGE, new Challenge(hash(code), now.plus(CODE_LIFETIME), 0));
		return code;
	}

	/** Checks the code; on success marks the session verified for {@link #VALIDITY}. */
	public static Instant verify(HttpSession session, String code, Instant now) {
		Object value = session.getAttribute(CHALLENGE);
		if (!(value instanceof Challenge challenge) || challenge.expiresAt().isBefore(now)) {
			session.removeAttribute(CHALLENGE);
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "STEP_UP_CODE_EXPIRED",
					"The code has expired. Request a new one.");
		}
		if (code == null || !MessageDigest.isEqual(hash(code.trim()).getBytes(), challenge.codeHash().getBytes())) {
			int attempts = challenge.attempts() + 1;
			if (attempts >= MAX_ATTEMPTS) {
				session.removeAttribute(CHALLENGE);
			}
			else {
				session.setAttribute(CHALLENGE, new Challenge(challenge.codeHash(), challenge.expiresAt(), attempts));
			}
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "STEP_UP_CODE_INVALID", "The code is not correct");
		}
		session.removeAttribute(CHALLENGE);
		Instant until = now.plus(VALIDITY);
		session.setAttribute(VERIFIED_UNTIL, until);
		return until;
	}

	static String hash(String code) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes()));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
