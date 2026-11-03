package com.selectcode.sms.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem responses in the project's shape: {@code code}, {@code requestId}, optional {@code errors}. */
public final class Problems {

	private static final String TYPE_BASE = "https://sms.selectcode.dev/problems/";

	private Problems() {
	}

	public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
		problem.setProperty("code", code);
		problem.setProperty("requestId", RequestIdFilter.current());
		return problem;
	}

	public static ProblemDetail validation(List<Map<String, String>> errors) {
		ProblemDetail problem = of(HttpStatusCode.valueOf(422), "VALIDATION_FAILED",
				"One or more fields are invalid");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", errors);
		return problem;
	}
}
