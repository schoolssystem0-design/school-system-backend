package com.selectcode.sms.fees.api;

import com.selectcode.sms.fees.app.FeeCollections;
import com.selectcode.sms.fees.app.FeeCollections.FollowUp;
import com.selectcode.sms.fees.app.FeeCollections.ReminderResult;
import com.selectcode.sms.fees.app.FeeDocuments;
import com.selectcode.sms.fees.app.FeeQueries;
import com.selectcode.sms.fees.app.FeeQueries.AdjustmentRow;
import com.selectcode.sms.fees.app.FeeQueries.CreditRow;
import com.selectcode.sms.fees.app.FeeQueries.DefaulterRow;
import com.selectcode.sms.fees.app.FeeQueries.FamilyReceivable;
import com.selectcode.sms.fees.app.FeeQueries.HeadReportRow;
import com.selectcode.sms.fees.app.FeeQueries.Ledger;
import com.selectcode.sms.fees.app.FeeQueries.MissingDiscountRow;
import com.selectcode.sms.fees.app.FeeQueries.StatusRow;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

/** Ledgers, fee status, defaulters, reminders and fee reports (FR 10.14–10.17). */
@RestController
@RequestMapping("/api/v1/fees")
class FeeReportsController {

	private static final String VIEW = FeeSetupController.VIEW;

	private static final String REPORTS = "hasAuthority('" + Permissions.FEES_REPORTS_VIEW + "')"
			+ FeeSetupController.ALL;

	private static final String REMIND = "hasAuthority('" + Permissions.FEES_REMINDERS_SEND + "')"
			+ FeeSetupController.ALL;

	record FollowUpRequest(@NotBlank @Size(max = 1000) String remark, LocalDate nextDate) {
	}

	record ReminderRequest(@NotEmpty List<Long> familyIds, @Size(max = 500) String message) {
	}

	private final FeeQueries queries;

	private final FeeCollections collections;

	private final FeeDocuments documents;

	private final MessageOutbox outbox;

	FeeReportsController(FeeQueries queries, FeeCollections collections, FeeDocuments documents, MessageOutbox outbox) {
		this.queries = queries;
		this.collections = collections;
		this.documents = documents;
		this.outbox = outbox;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/students/{studentId}/ledger")
	Ledger studentLedger(@PathVariable long studentId) {
		return queries.studentLedger(studentId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/families/{familyId}/ledger")
	Ledger familyLedger(@PathVariable long familyId) {
		return queries.familyLedger(familyId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/status")
	PageResponse<StatusRow> status(@RequestParam(required = false) Long classId,
			@RequestParam(required = false) Long sectionId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return queries.status(classId, sectionId, status, q, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/defaulters")
	List<DefaulterRow> defaulters(@RequestParam(required = false) String minBalance,
			@RequestParam(required = false) Integer minDaysOverdue, @RequestParam(required = false) Long classId) {
		return queries.defaulters(minBalance, minDaysOverdue, classId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/families/{familyId}/follow-ups")
	List<FollowUp> followUps(@PathVariable long familyId) {
		return collections.followUps(familyId);
	}

	@PreAuthorize(REMIND)
	@PostMapping("/families/{familyId}/follow-ups")
	List<FollowUp> addFollowUp(@PathVariable long familyId, @Valid @RequestBody FollowUpRequest body) {
		return collections.addFollowUp(familyId, body.remark(), body.nextDate());
	}

	@PreAuthorize(REMIND)
	@PostMapping(value = "/families/{familyId}/final-reminder", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> finalReminder(@PathVariable long familyId) {
		return BillingController.pdf(documents.finalReminder(familyId), "final-reminder.pdf");
	}

	/** Requires an Idempotency-Key header; a retry never messages a family twice. */
	@PreAuthorize(REMIND)
	@PostMapping("/reminders")
	ReminderResult remind(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody ReminderRequest body) {
		return collections.sendReminders(body.familyIds(), body.message(), key);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/messages")
	List<MessageOutbox.Queued> messages(@RequestParam(required = false) String status) {
		return outbox.list("FEE_", status, 200);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/heads")
	List<HeadReportRow> headReport(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return queries.headReport(from, to);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/collections")
	List<HeadReportRow> collections(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return queries.collectionReport(from, to);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/family-receivables")
	List<FamilyReceivable> familyReceivables() {
		return queries.familyReceivables();
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/write-offs")
	List<AdjustmentRow> writeOffs(@RequestParam(defaultValue = "BAD_DEBT") String kind) {
		return queries.writeOffs(kind);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/credits")
	List<CreditRow> credits() {
		return queries.credits();
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/missing-sibling-discounts")
	List<MissingDiscountRow> missingSiblingDiscounts() {
		return queries.missingSiblingDiscounts();
	}
}
