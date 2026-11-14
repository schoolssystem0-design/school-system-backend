package com.selectcode.sms.shared.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApi(ApiException ex) {
		return Problems.of(ex.status(), ex.code(), ex.getMessage());
	}

	@ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
	ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
		return Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this action");
	}

	@ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
	ProblemDetail handleAuthentication(org.springframework.security.core.AuthenticationException ex) {
		return Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue");
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
			org.springframework.web.multipart.MaxUploadSizeExceededException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return ResponseEntity.unprocessableContent()
			.body(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_TOO_LARGE", "The file is too large"));
	}

	@ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
	ProblemDetail handleIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
		// Unique/foreign key violations that slipped past service checks: a conflict, not a server error.
		// Log only the constraint name: the database detail can contain personal data (CLAUDE.md: no PII in logs).
		String message = String.valueOf(ex.getMostSpecificCause().getMessage());
		java.util.regex.Matcher constraint = java.util.regex.Pattern.compile("constraint \"(\\w+)\"").matcher(message);
		log.warn("Data integrity violation on constraint {}", constraint.find() ? constraint.group(1) : "unknown");
		return Problems.of(HttpStatus.CONFLICT, "CONFLICT", "This change conflicts with existing data");
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail handleVersionConflict(ObjectOptimisticLockingFailureException ex) {
		return Problems.of(HttpStatus.CONFLICT, "CONFLICT_VERSION",
				"The record was changed by someone else. Reload and try again.");
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled error", ex);
		return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"Something went wrong. Quote the request ID when reporting this.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "code",
					error.getCode() == null ? "INVALID" : error.getCode().toUpperCase(), "message",
					error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
			.toList();
		return ResponseEntity.unprocessableContent().body(Problems.validation(errors));
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
			WebRequest request) {
		// Spring MVC's own errors (400 malformed JSON, 405, 415…) get the same code/requestId shape.
		if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
			problem.setProperty("code", status.value() == 400 ? "MALFORMED_REQUEST" : "HTTP_" + status.value());
			problem.setProperty("requestId", RequestIdFilter.current());
		}
		return super.createResponseEntity(body, headers, status, request);
	}
}
