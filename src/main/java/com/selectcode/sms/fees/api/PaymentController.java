package com.selectcode.sms.fees.api;

import com.selectcode.sms.fees.app.AdjustmentService;
import com.selectcode.sms.fees.app.AdjustmentService.AdjustmentInput;
import com.selectcode.sms.fees.app.AdjustmentService.AdjustmentView;
import com.selectcode.sms.fees.app.FeeDocuments;
import com.selectcode.sms.fees.app.OpenLines;
import com.selectcode.sms.fees.app.OpenLines.OpenLine;
import com.selectcode.sms.fees.app.PaymentService;
import com.selectcode.sms.fees.app.PaymentService.AllocationInput;
import com.selectcode.sms.fees.app.PaymentService.PaymentInput;
import com.selectcode.sms.fees.app.PaymentService.PaymentRow;
import com.selectcode.sms.fees.app.PaymentService.PaymentView;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payments, receipts, reversals and corrections (FR 10.7–10.10, 10.13, 10.18). */
@RestController
@RequestMapping("/api/v1/fees")
class PaymentController {

	private static final String VIEW = FeeSetupController.VIEW;

	private static final String ADD = "hasAuthority('" + Permissions.FEES_PAYMENT_ADD + "')" + FeeSetupController.ALL;

	private static final String REVERSE = "hasAuthority('" + Permissions.FEES_PAYMENT_REVERSE + "')"
			+ FeeSetupController.ALL;

	private static final String ADJUST = "hasAuthority('" + Permissions.FEES_ADJUST_REQUEST + "')"
			+ FeeSetupController.ALL;

	private static final String ADJUST_APPROVE = "hasAuthority('" + Permissions.FEES_ADJUST_APPROVE + "')"
			+ FeeSetupController.ALL;

	record AllocationRequest(@NotNull Long billLineId, @NotBlank String amount) {
	}

	record PaymentRequest(Long familyId, Long studentId, @NotBlank String amount, LocalDate paymentDate,
			@NotBlank String method, @NotNull Long accountId, @Size(max = 100) String reference,
			@Size(max = 500) String remarks, List<@Valid AllocationRequest> allocations) {
	}

	record ReverseRequest(@NotBlank @Size(max = 500) String reason) {
	}

	record AdjustmentRequest(@NotNull Long billLineId, @NotBlank String amount, String kind,
			@NotBlank @Size(max = 500) String reason) {
	}

	record AdjustmentDecisionRequest(@Size(max = 500) String note) {
	}

	private final PaymentService payments;

	private final AdjustmentService adjustments;

	private final OpenLines openLines;

	private final FeeDocuments documents;

	PaymentController(PaymentService payments, AdjustmentService adjustments, OpenLines openLines,
			FeeDocuments documents) {
		this.payments = payments;
		this.adjustments = adjustments;
		this.openLines = openLines;
		this.documents = documents;
	}

	/**
	 * Requires an Idempotency-Key header. A retry with the same key returns the first payment (200) instead of
	 * creating a second one.
	 */
	@PreAuthorize(ADD)
	@PostMapping("/payments")
	ResponseEntity<PaymentView> pay(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody PaymentRequest body) {
		PaymentView view = payments.record(new PaymentInput(body.familyId(), body.studentId(), body.amount(),
				body.paymentDate(), body.method(), body.accountId(), body.reference(), body.remarks(),
				body.allocations() == null ? null
						: body.allocations().stream().map(a -> new AllocationInput(a.billLineId(), a.amount())).toList()),
				key);
		return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/payments")
	PageResponse<PaymentRow> list(@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to, @RequestParam(required = false) String method,
			@RequestParam(required = false) Long accountId, @RequestParam(required = false) Long familyId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return payments.list(from, to, method, accountId, familyId, q, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/payments/{id}")
	PaymentView get(@PathVariable long id) {
		return payments.get(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/payments/{id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> receipt(@PathVariable long id) {
		return BillingController.pdf(documents.receipt(id), "receipt.pdf");
	}

	@PreAuthorize(REVERSE)
	@RequiresStepUp
	@PostMapping("/payments/{id}/reverse")
	PaymentView reverse(@PathVariable long id, @Valid @RequestBody ReverseRequest body) {
		return payments.reverse(id, body.reason());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/families/{familyId}/open-lines")
	List<OpenLine> openLines(@PathVariable long familyId, @RequestParam(required = false) Long studentId) {
		return openLines.forFamily(familyId, studentId);
	}

	@PreAuthorize(ADD)
	@PostMapping("/families/{familyId}/apply-credit")
	java.util.Map<String, String> applyCredit(@PathVariable long familyId) {
		return java.util.Map.of("applied", payments.applyCredit(familyId));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/adjustments")
	List<AdjustmentView> adjustments(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long studentId) {
		return adjustments.list(status, studentId);
	}

	@PreAuthorize(ADJUST)
	@PostMapping("/adjustments")
	ResponseEntity<AdjustmentView> requestAdjustment(@Valid @RequestBody AdjustmentRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(adjustments.request(new AdjustmentInput(body.billLineId(), body.amount(), body.kind(), body.reason())));
	}

	@PreAuthorize(ADJUST_APPROVE)
	@RequiresStepUp
	@PostMapping("/adjustments/{id}/approve")
	AdjustmentView approve(@PathVariable long id,
			@Valid @RequestBody(required = false) AdjustmentDecisionRequest body) {
		return adjustments.decide(id, true, body == null ? null : body.note());
	}

	@PreAuthorize(ADJUST_APPROVE)
	@PostMapping("/adjustments/{id}/reject")
	AdjustmentView reject(@PathVariable long id, @Valid @RequestBody AdjustmentDecisionRequest body) {
		return adjustments.decide(id, false, body.note());
	}
}
