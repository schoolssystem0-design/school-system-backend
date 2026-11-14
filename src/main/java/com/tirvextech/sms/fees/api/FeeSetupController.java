package com.selectcode.sms.fees.api;

import com.selectcode.sms.fees.app.DiscountService;
import com.selectcode.sms.fees.app.DiscountService.DiscountInput;
import com.selectcode.sms.fees.app.DiscountService.DiscountView;
import com.selectcode.sms.fees.app.FeeLedger;
import com.selectcode.sms.fees.app.FeeSetupService;
import com.selectcode.sms.fees.app.FeeSetupService.ClassAmount;
import com.selectcode.sms.fees.app.FeeSetupService.HeadInput;
import com.selectcode.sms.fees.app.FeeSetupService.HeadView;
import com.selectcode.sms.fees.app.FeeSetupService.MissingAmount;
import com.selectcode.sms.fees.app.FeeSetupService.OverrideView;
import com.selectcode.sms.fees.app.FeeSetupService.SiblingRule;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Fee heads, class and student amounts, sibling rules and discounts (FR 10.1–10.4). */
@RestController
@RequestMapping("/api/v1/fees")
class FeeSetupController {

	// Fee data is school-wide: users limited to their own classes or records never see it.
	static final String ALL = " and hasAuthority('SCOPE_ALL')";

	static final String VIEW = "hasAuthority('" + Permissions.FEES_VIEW + "')" + ALL;

	static final String SETUP = "hasAuthority('" + Permissions.FEES_SETUP + "')" + ALL;

	static final String DISCOUNT = "hasAuthority('" + Permissions.FEES_DISCOUNT_MANAGE + "')" + ALL;

	static final String DISCOUNT_APPROVE = "hasAuthority('" + Permissions.FEES_DISCOUNT_APPROVE + "')" + ALL;

	record FeeHeadRequest(@NotBlank @Size(max = 80) String name, @NotBlank String frequency, String maxAmount,
			Integer displayOrder, boolean refundable, Long version) {
	}

	record HeadStatusRequest(boolean active) {
	}

	record ClassAmountRequest(@NotNull Long classId, @NotNull Long feeHeadId, String amount) {
	}

	record OverrideRequest(@NotNull Long academicYearId, @NotNull Long feeHeadId, String amount,
			@NotBlank @Size(max = 500) String reason) {
	}

	record SiblingRuleRequest(int childOrder, @NotBlank String percent, List<Long> feeHeadIds) {
	}

	record DiscountRequest(@NotNull Long studentId, Long feeHeadId, @NotBlank String kind, @NotBlank String value,
			@NotBlank @Size(max = 500) String reason, LocalDate validFrom, LocalDate validTo) {
	}

	record DecisionRequest(@Size(max = 500) String note) {
	}

	record EndDiscountRequest(LocalDate lastDay, @NotBlank @Size(max = 500) String reason) {
	}

	private final FeeSetupService setup;

	private final DiscountService discounts;

	private final FeeLedger ledger;

	FeeSetupController(FeeSetupService setup, DiscountService discounts, FeeLedger ledger) {
		this.setup = setup;
		this.discounts = discounts;
		this.ledger = ledger;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/heads")
	List<HeadView> heads() {
		return setup.heads();
	}

	@PreAuthorize(SETUP)
	@PostMapping("/heads")
	@ResponseStatus(HttpStatus.CREATED)
	HeadView createHead(@Valid @RequestBody FeeHeadRequest body) {
		return setup.createHead(input(body));
	}

	@PreAuthorize(SETUP)
	@PatchMapping("/heads/{id}")
	HeadView updateHead(@PathVariable long id, @Valid @RequestBody FeeHeadRequest body) {
		return setup.updateHead(id, input(body), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize(SETUP)
	@PostMapping("/heads/{id}/status")
	HeadView headStatus(@PathVariable long id, @RequestBody HeadStatusRequest body) {
		return setup.setHeadStatus(id, body.active());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/years/{yearId}/class-amounts")
	List<ClassAmount> classAmounts(@PathVariable long yearId) {
		return setup.classAmounts(yearId);
	}

	@PreAuthorize(SETUP)
	@PutMapping("/years/{yearId}/class-amounts")
	List<ClassAmount> saveClassAmounts(@PathVariable long yearId,
			@Valid @RequestBody List<@Valid ClassAmountRequest> body) {
		return setup.saveClassAmounts(yearId,
				body.stream().map(c -> new ClassAmount(c.classId(), c.feeHeadId(), c.amount())).toList());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/years/{yearId}/missing-amounts")
	List<MissingAmount> missingAmounts(@PathVariable long yearId) {
		return setup.missingAmounts(yearId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/students/{studentId}/overrides")
	List<OverrideView> overrides(@PathVariable long studentId, @RequestParam long yearId) {
		return setup.overrides(studentId, yearId);
	}

	@PreAuthorize(SETUP)
	@PutMapping("/students/{studentId}/overrides")
	List<OverrideView> saveOverride(@PathVariable long studentId, @Valid @RequestBody OverrideRequest body) {
		return setup.saveOverride(studentId, body.academicYearId(), body.feeHeadId(), body.amount(), body.reason());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/sibling-rules")
	List<SiblingRule> siblingRules() {
		return setup.siblingRules();
	}

	@PreAuthorize(SETUP)
	@PutMapping("/sibling-rules")
	List<SiblingRule> saveSiblingRules(@Valid @RequestBody List<@Valid SiblingRuleRequest> body) {
		return setup.saveSiblingRules(
				body.stream().map(r -> new SiblingRule(0, r.childOrder(), r.percent(), r.feeHeadIds())).toList());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/accounts")
	List<FeeLedger.AccountView> accounts() {
		return ledger.receivingAccounts();
	}

	@PreAuthorize(VIEW)
	@GetMapping("/discounts")
	List<DiscountView> discounts(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long studentId) {
		return discounts.list(status, studentId);
	}

	@PreAuthorize(DISCOUNT)
	@PostMapping("/discounts")
	@ResponseStatus(HttpStatus.CREATED)
	DiscountView createDiscount(@Valid @RequestBody DiscountRequest body) {
		return discounts.create(new DiscountInput(body.studentId(), body.feeHeadId(), body.kind(), body.value(),
				body.reason(), body.validFrom(), body.validTo()));
	}

	@PreAuthorize(DISCOUNT_APPROVE)
	@PostMapping("/discounts/{id}/approve")
	DiscountView approveDiscount(@PathVariable long id, @Valid @RequestBody(required = false) DecisionRequest body) {
		return discounts.decide(id, true, body == null ? null : body.note());
	}

	@PreAuthorize(DISCOUNT_APPROVE)
	@PostMapping("/discounts/{id}/reject")
	DiscountView rejectDiscount(@PathVariable long id, @Valid @RequestBody DecisionRequest body) {
		return discounts.decide(id, false, body.note());
	}

	@PreAuthorize(DISCOUNT)
	@PostMapping("/discounts/{id}/end")
	DiscountView endDiscount(@PathVariable long id, @Valid @RequestBody EndDiscountRequest body) {
		return discounts.end(id, body.lastDay(), body.reason());
	}

	private static HeadInput input(FeeHeadRequest body) {
		return new HeadInput(body.name(), body.frequency(), body.maxAmount(), body.displayOrder(), body.refundable());
	}
}
