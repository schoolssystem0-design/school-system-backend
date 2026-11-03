package com.selectcode.sms.communication.api;

import com.selectcode.sms.communication.app.CommunicationService;
import com.selectcode.sms.communication.app.CommunicationService.Audience;
import com.selectcode.sms.communication.app.CommunicationService.DeliveryLog;
import com.selectcode.sms.communication.app.CommunicationService.SendPreview;
import com.selectcode.sms.communication.app.CommunicationService.SendReport;
import com.selectcode.sms.communication.app.CommunicationService.TemplateView;
import com.selectcode.sms.communication.app.CommunicationService.WelcomeResult;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Messages to families, enquiries and staff, templates and the delivery log (FR 8.6). */
@RestController
@RequestMapping("/api/v1/communication")
class CommunicationController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String VIEW = "hasAuthority('" + Permissions.MESSAGES_VIEW + "')" + ALL;

	private static final String SEND = "hasAuthority('" + Permissions.MESSAGES_SEND + "')" + ALL;

	private static final String TEMPLATES = "hasAuthority('" + Permissions.MESSAGES_TEMPLATES_MANAGE + "')" + ALL;

	record MessageRequest(@NotBlank String audience, Long classId, Long sectionId, List<Long> studentIds,
			@NotBlank @Size(max = 500) String message) {

		Audience target() {
			return new Audience(audience, classId, sectionId, studentIds);
		}
	}

	record TemplateRequest(@NotBlank @Size(max = 500) String body) {
	}

	private final CommunicationService messages;

	CommunicationController(CommunicationService messages) {
		this.messages = messages;
	}

	/** Who would receive it, how many SMS parts and the estimated cost. Nothing is sent. */
	@PreAuthorize(SEND)
	@PostMapping("/preview")
	SendPreview preview(@Valid @RequestBody MessageRequest body) {
		return messages.preview(body.target(), body.message());
	}

	/** Requires an Idempotency-Key header; a retry never messages a phone twice. */
	@PreAuthorize(SEND)
	@PostMapping("/send")
	SendReport send(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody MessageRequest body) {
		return messages.send(body.target(), body.message(), key);
	}

	/** Safe to repeat: a student's family is welcomed once. */
	@PreAuthorize(SEND)
	@PostMapping("/welcome/{studentId}")
	WelcomeResult welcome(@PathVariable long studentId) {
		return messages.welcome(studentId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/log")
	DeliveryLog log(@RequestParam(required = false) String purpose, @RequestParam(required = false) String status) {
		return messages.log(purpose, status);
	}

	@PreAuthorize(SEND)
	@PostMapping("/messages/{id}/retry")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void retry(@PathVariable long id) {
		messages.retry(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/templates")
	List<TemplateView> templates() {
		return messages.templates();
	}

	@PreAuthorize(TEMPLATES)
	@PutMapping("/templates/{code}")
	TemplateView saveTemplate(@PathVariable String code, @Valid @RequestBody TemplateRequest body) {
		return messages.saveTemplate(code, body.body());
	}

	@PreAuthorize(TEMPLATES)
	@DeleteMapping("/templates/{code}")
	TemplateView resetTemplate(@PathVariable String code) {
		return messages.resetTemplate(code);
	}
}
