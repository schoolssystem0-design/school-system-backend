package com.selectcode.sms.iam.api;

import com.selectcode.sms.iam.app.SecurityLogQueries;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.PageResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SecurityLogController {

	private final SecurityLogQueries queries;

	SecurityLogController(SecurityLogQueries queries) {
		this.queries = queries;
	}

	/** Login history and failed attempts (FR 2.7). */
	@PreAuthorize("hasAuthority('" + Permissions.IAM_LOGIN_HISTORY_VIEW + "')")
	@GetMapping("/iam/login-history")
	PageResponse<SecurityLogQueries.LoginRow> logins(@RequestParam(required = false) String username,
			@RequestParam(required = false) String result,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.logins(username, result, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	/** Activity history / audit trail (FR 2.8). */
	@PreAuthorize("hasAuthority('" + Permissions.AUDIT_VIEW + "')")
	@GetMapping("/audit")
	PageResponse<SecurityLogQueries.AuditRow> activity(@RequestParam(required = false) String q,
			@RequestParam(required = false) String entityType,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.activity(q, entityType, from, to, PageResponse.page(page), PageResponse.size(size));
	}
}
