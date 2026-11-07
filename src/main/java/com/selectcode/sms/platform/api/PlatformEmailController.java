package com.selectcode.sms.platform.api;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.EmailSetup;
import com.selectcode.sms.shared.messaging.EmailSetup.EmailStatus;
import com.selectcode.sms.shared.security.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email set-up check for the system owner: is an email service configured, what is wrong with it, and a test email.
 * Verification codes, password resets and setup links depend on it, so it is checked here instead of in server logs.
 */
@RestController
@RequestMapping("/api/v1/platform/email")
class PlatformEmailController {

	private static final String SUPER_ADMIN = "hasAuthority('" + CurrentActor.SUPER_ADMIN_AUTHORITY + "')";

	record EmailTestRequest(@NotBlank @Email @Size(max = 254) String to) {
	}

	private final EmailSetup email;

	private final AuditService audit;

	PlatformEmailController(EmailSetup email, AuditService audit) {
		this.email = email;
		this.audit = audit;
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping
	EmailStatus status() {
		return email.status();
	}

	/** Sends a test email now and reports exactly why it failed, if it did. */
	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/test")
	EmailSetup.EmailTestResult test(@Valid @RequestBody EmailTestRequest body) {
		EmailSetup.EmailTestResult result = email.sendTest(body.to().trim());
		audit.recordPlatform("EMAIL_TEST_SENT", null, Map.of("to", body.to().trim(), "sent", result.sent()));
		return result;
	}
}
