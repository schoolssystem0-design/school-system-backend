package com.selectcode.sms.shared.web;

import org.springframework.http.HttpStatus;

/**
 * A business error with a stable machine-readable {@code code} (see docs/API-CONVENTIONS.md). The frontend maps codes
 * to user-facing text; {@code message} is an English explanation for logs and developers.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
	}

	public static ApiException businessRule(String code, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
