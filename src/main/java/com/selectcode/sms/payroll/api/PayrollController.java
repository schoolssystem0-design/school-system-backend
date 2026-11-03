package com.selectcode.sms.payroll.api;

import com.selectcode.sms.payroll.app.PayrollService;
import com.selectcode.sms.payroll.app.PayrollService.LineInput;
import com.selectcode.sms.payroll.app.PayrollService.SheetRow;
import com.selectcode.sms.payroll.app.PayrollService.SheetView;
import com.selectcode.sms.payroll.app.SalarySlips;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Salary sheets and slips (FR 6.8, 13.12). Every change needs a fresh verification code. */
@RestController
@RequestMapping("/api/v1/payroll")
class PayrollController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String VIEW = "hasAuthority('" + Permissions.PAYROLL_VIEW + "')" + ALL;

	private static final String MANAGE = "hasAuthority('" + Permissions.PAYROLL_MANAGE + "')" + ALL;

	private static final String APPROVE = "hasAuthority('" + Permissions.PAYROLL_APPROVE + "')" + ALL;

	private static final String PAY = "hasAuthority('" + Permissions.PAYROLL_PAY + "')" + ALL;

	record SheetRequest(@NotBlank String month) {
	}

	record SalaryLineRequest(int absentDays, int halfDays, int lateDays, int leaveDays, int lectures, String allowance,
			String tax, String otherDeduction, @Size(max = 300) String otherNote) {
	}

	record PayRequest(@NotNull Long accountId, LocalDate paymentDate) {
	}

	record SheetReverseRequest(@NotBlank @Size(max = 500) String reason) {
	}

	private final PayrollService payroll;

	private final SalarySlips slips;

	PayrollController(PayrollService payroll, SalarySlips slips) {
		this.payroll = payroll;
		this.slips = slips;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/sheets")
	List<SheetRow> sheets() {
		return payroll.sheets();
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PostMapping("/sheets")
	@ResponseStatus(HttpStatus.CREATED)
	SheetView generate(@Valid @RequestBody SheetRequest body) {
		return payroll.generate(body.month());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/sheets/{id}")
	SheetView sheet(@PathVariable long id) {
		return payroll.get(id);
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PutMapping("/lines/{lineId}")
	SheetView updateLine(@PathVariable long lineId, @Valid @RequestBody SalaryLineRequest body) {
		return payroll.updateLine(lineId, new LineInput(body.absentDays(), body.halfDays(), body.lateDays(),
				body.leaveDays(), body.lectures(), body.allowance(), body.tax(), body.otherDeduction(), body.otherNote()));
	}

	@PreAuthorize(APPROVE)
	@RequiresStepUp
	@PostMapping("/sheets/{id}/approve")
	SheetView approve(@PathVariable long id) {
		return payroll.approve(id);
	}

	/** Requires an Idempotency-Key header; a retry never pays twice. */
	@PreAuthorize(PAY)
	@RequiresStepUp
	@PostMapping("/sheets/{id}/pay")
	SheetView pay(@PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody PayRequest body) {
		return payroll.pay(id, body.accountId(), body.paymentDate(), key);
	}

	@PreAuthorize(APPROVE)
	@RequiresStepUp
	@PostMapping("/sheets/{id}/reverse")
	SheetView reverse(@PathVariable long id, @Valid @RequestBody SheetReverseRequest body) {
		return payroll.reverse(id, body.reason());
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/sheets/{id}/slips", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> slips(@PathVariable long id, @RequestParam(required = false) Long lineId) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("salary-slips.pdf").build().toString())
			.body(slips.slips(id, lineId));
	}
}
