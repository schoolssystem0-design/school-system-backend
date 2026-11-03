package com.selectcode.sms.fees.api;

import com.selectcode.sms.fees.app.BillingService;
import com.selectcode.sms.fees.app.BillingService.BillRunInput;
import com.selectcode.sms.fees.app.BillingService.LateFeeInput;
import com.selectcode.sms.fees.app.BillingService.PlannedLine;
import com.selectcode.sms.fees.app.BillingService.RunReport;
import com.selectcode.sms.fees.app.BillingService.RunView;
import com.selectcode.sms.fees.app.FeeDocuments;
import com.selectcode.sms.fees.app.FeeQueries;
import com.selectcode.sms.fees.app.FeeQueries.BillRow;
import com.selectcode.sms.fees.app.FeeQueries.BillView;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

/** Bill runs, late fees, bills and vouchers (FR 10.5, 10.6, 10.11, 4.10). */
@RestController
@RequestMapping("/api/v1/fees")
class BillingController {

	private static final String VIEW = FeeSetupController.VIEW;

	private static final String GENERATE = "hasAuthority('" + Permissions.FEES_BILL_GENERATE + "')"
			+ FeeSetupController.ALL;

	record BillRunRequest(@Size(max = 200) String title, LocalDate issueDate, LocalDate dueDate, String period,
			@Size(max = 30) String termLabel, List<Long> headIds, List<Long> classIds, List<Long> sectionIds,
			List<Long> studentIds, List<Long> familyIds, List<Long> excludeStudentIds, Boolean applyCredit) {
	}

	record LateFeeRequest(LocalDate dueOnOrBefore, String amount, List<Long> excludeClassIds) {
	}

	record VoucherRequest(List<Long> billIds, Long runId, Long sectionId) {
	}

	private final BillingService billing;

	private final FeeQueries queries;

	private final FeeDocuments documents;

	BillingController(BillingService billing, FeeQueries queries, FeeDocuments documents) {
		this.billing = billing;
		this.queries = queries;
		this.documents = documents;
	}

	/** Requires an Idempotency-Key header: repeating the request returns the first run's report. */
	@PreAuthorize(GENERATE)
	@PostMapping("/bill-runs")
	RunReport run(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody BillRunRequest body) {
		return billing.run(new BillRunInput(body.title(), body.issueDate(), body.dueDate(), body.period(),
				body.termLabel(), body.headIds(), body.classIds(), body.sectionIds(), body.studentIds(),
				body.familyIds(), body.excludeStudentIds(), body.applyCredit()), key);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/bill-runs")
	List<RunView> runs() {
		return billing.runs();
	}

	@PreAuthorize(GENERATE)
	@PostMapping("/late-fee-runs")
	RunReport lateFee(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody LateFeeRequest body) {
		return billing.lateFee(new LateFeeInput(body.dueOnOrBefore(), body.amount(), body.excludeClassIds()), key);
	}

	record AbsentFineRequest(String month) {
	}

	/** Requires an Idempotency-Key header. */
	@PreAuthorize(GENERATE)
	@PostMapping("/absent-fine-runs")
	RunReport absentFine(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestBody AbsentFineRequest body) {
		return billing.absentFine(body.month(), key);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/students/{studentId}/admission-proposal")
	List<PlannedLine> admissionProposal(@PathVariable long studentId) {
		return billing.admissionProposal(studentId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/bills")
	PageResponse<BillRow> bills(@RequestParam(required = false) Long studentId,
			@RequestParam(required = false) Long familyId, @RequestParam(required = false) Long classId,
			@RequestParam(required = false) Long sectionId, @RequestParam(required = false) Long runId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.bills(studentId, familyId, classId, sectionId, runId, status, q, PageResponse.page(page),
				PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/bills/{id}")
	BillView bill(@PathVariable long id) {
		return queries.bill(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/bills/{id}/voucher", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> voucher(@PathVariable long id) {
		return pdf(documents.vouchers(List.of(id)), "voucher.pdf");
	}

	@PreAuthorize(VIEW)
	@PostMapping(value = "/vouchers", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> vouchers(@RequestBody VoucherRequest body) {
		return pdf(documents.vouchers(documents.billsFor(body.billIds(), body.runId(), body.sectionId())),
				"vouchers.pdf");
	}

	static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
			.body(bytes);
	}
}
