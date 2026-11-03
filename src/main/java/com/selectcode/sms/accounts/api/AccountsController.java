package com.selectcode.sms.accounts.api;

import com.selectcode.sms.accounts.app.AccountsSetupService;
import com.selectcode.sms.accounts.app.AccountsSetupService.MoneyAccountView;
import com.selectcode.sms.accounts.app.AccountsSetupService.CategoryView;
import com.selectcode.sms.accounts.app.AccountsSetupService.VendorView;
import com.selectcode.sms.accounts.app.BooksService;
import com.selectcode.sms.accounts.app.BooksService.AccountLedger;
import com.selectcode.sms.accounts.app.BooksService.ActivityRow;
import com.selectcode.sms.accounts.app.BooksService.BalanceRow;
import com.selectcode.sms.accounts.app.BooksService.BalanceSheet;
import com.selectcode.sms.accounts.app.BooksService.CategoryRow;
import com.selectcode.sms.accounts.app.BooksService.DayCloseView;
import com.selectcode.sms.accounts.app.BooksService.DaySummary;
import com.selectcode.sms.accounts.app.BooksService.LedgerLine;
import com.selectcode.sms.accounts.app.BooksService.MonthRow;
import com.selectcode.sms.accounts.app.MoneyEntryService;
import com.selectcode.sms.accounts.app.MoneyEntryService.EntryInput;
import com.selectcode.sms.accounts.app.MoneyEntryService.EntryView;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * School accounts (FR 11). Every change needs a fresh verification code (plan Phase 4) and the "all classes" data
 * scope; reads need the view permission.
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountsController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String VIEW = "hasAuthority('" + Permissions.ACCOUNTS_VIEW + "')" + ALL;

	private static final String MANAGE = "hasAuthority('" + Permissions.ACCOUNTS_MANAGE + "')" + ALL;

	private static final String ADD = "hasAuthority('" + Permissions.ACCOUNTS_ENTRY_ADD + "')" + ALL;

	private static final String APPROVE = "hasAuthority('" + Permissions.ACCOUNTS_ENTRY_APPROVE + "')" + ALL;

	private static final String CLOSE = "hasAuthority('" + Permissions.ACCOUNTS_DAY_CLOSE + "')" + ALL;

	private static final String RECONCILE = "hasAuthority('" + Permissions.ACCOUNTS_RECONCILE + "')" + ALL;

	private static final String REPORTS = "hasAuthority('" + Permissions.ACCOUNTS_REPORTS_VIEW + "')" + ALL;

	record MoneyAccountRequest(@NotBlank @Size(max = 100) String name, @NotBlank String subtype,
			@Size(max = 200) String bankDetails, String openingBalance, LocalDate openingDate) {
	}

	record MoneyAccountUpdate(@NotBlank @Size(max = 100) String name, @Size(max = 200) String bankDetails,
			boolean active) {
	}

	record OpeningRequest(@NotBlank String amount, LocalDate date) {
	}

	record CategoryRequest(@NotBlank @Size(max = 80) String name, String kind, Boolean active) {
	}

	record VendorRequest(@NotBlank @Size(max = 200) String name, @Size(max = 30) String phone,
			@Size(max = 500) String address, @Size(max = 500) String notes, Boolean active) {
	}

	record EntryRequest(@NotBlank String kind, LocalDate entryDate, Long categoryId, Long accountId, Long toAccountId,
			Long vendorId, @NotBlank String amount, @Size(max = 100) String reference,
			@Size(max = 500) String description, Long attachmentFileId) {
	}

	record EntryDecisionRequest(@Size(max = 500) String note) {
	}

	record EntryReverseRequest(@NotBlank @Size(max = 500) String reason) {
	}

	record CloseRequest(@NotNull LocalDate date, @NotBlank String counted, @Size(max = 500) String note) {
	}

	record ClearRequest(@NotEmpty List<Long> lineIds, LocalDate clearedOn, boolean cleared) {
	}

	private final AccountsSetupService setup;

	private final MoneyEntryService entries;

	private final BooksService books;

	AccountsController(AccountsSetupService setup, MoneyEntryService entries, BooksService books) {
		this.setup = setup;
		this.entries = entries;
		this.books = books;
	}

	// ----------------------------------------------------------- set-up

	@PreAuthorize(VIEW)
	@GetMapping("/money-accounts")
	List<MoneyAccountView> moneyAccounts(@RequestParam(defaultValue = "false") boolean includeInactive) {
		return setup.moneyAccounts(includeInactive);
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PostMapping("/money-accounts")
	@ResponseStatus(HttpStatus.CREATED)
	MoneyAccountView createMoneyAccount(@Valid @RequestBody MoneyAccountRequest body) {
		return setup.createMoneyAccount(body.name(), body.subtype(), body.bankDetails(), body.openingBalance(),
				body.openingDate());
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PatchMapping("/money-accounts/{id}")
	MoneyAccountView updateMoneyAccount(@PathVariable long id, @Valid @RequestBody MoneyAccountUpdate body) {
		return setup.updateMoneyAccount(id, body.name(), body.bankDetails(), body.active());
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PostMapping("/money-accounts/{id}/opening-balance")
	MoneyAccountView openingBalance(@PathVariable long id, @Valid @RequestBody OpeningRequest body) {
		return setup.addOpeningBalance(id, body.amount(), body.date());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/categories")
	List<CategoryView> categories(@RequestParam(required = false) String kind) {
		return setup.categories(kind == null || kind.isBlank() ? null : kind);
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PostMapping("/categories")
	@ResponseStatus(HttpStatus.CREATED)
	CategoryView createCategory(@Valid @RequestBody CategoryRequest body) {
		return setup.createCategory(body.name(), body.kind());
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PatchMapping("/categories/{id}")
	CategoryView updateCategory(@PathVariable long id, @Valid @RequestBody CategoryRequest body) {
		return setup.updateCategory(id, body.name(), body.active() == null || body.active());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/vendors")
	List<VendorView> vendors() {
		return setup.vendors();
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PostMapping("/vendors")
	@ResponseStatus(HttpStatus.CREATED)
	VendorView createVendor(@Valid @RequestBody VendorRequest body) {
		return setup.saveVendor(null, body.name(), body.phone(), body.address(), body.notes(), true);
	}

	@PreAuthorize(MANAGE)
	@RequiresStepUp
	@PatchMapping("/vendors/{id}")
	VendorView updateVendor(@PathVariable long id, @Valid @RequestBody VendorRequest body) {
		return setup.saveVendor(id, body.name(), body.phone(), body.address(), body.notes(),
				body.active() == null || body.active());
	}

	// ----------------------------------------------------------- entries

	@PreAuthorize(VIEW)
	@GetMapping("/entries")
	PageResponse<EntryView> entries(@RequestParam(required = false) String kind,
			@RequestParam(required = false) String status, @RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long accountId,
			@RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long vendorId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return entries.list(kind, status, from, to, accountId, categoryId, vendorId, q, PageResponse.page(page),
				PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/entries/{id}")
	EntryView entry(@PathVariable long id) {
		return entries.get(id);
	}

	/** Requires an Idempotency-Key header; the same key returns the first entry. */
	@PreAuthorize(ADD)
	@RequiresStepUp
	@PostMapping("/entries")
	ResponseEntity<EntryView> createEntry(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody EntryRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(entries.create(new EntryInput(body.kind(), body.entryDate(), body.categoryId(), body.accountId(),
					body.toAccountId(), body.vendorId(), body.amount(), body.reference(), body.description(),
					body.attachmentFileId()), key));
	}

	@PreAuthorize(APPROVE)
	@RequiresStepUp
	@PostMapping("/entries/{id}/approve")
	EntryView approve(@PathVariable long id, @Valid @RequestBody(required = false) EntryDecisionRequest body) {
		return entries.decide(id, true, body == null ? null : body.note());
	}

	@PreAuthorize(APPROVE)
	@RequiresStepUp
	@PostMapping("/entries/{id}/reject")
	EntryView reject(@PathVariable long id, @Valid @RequestBody EntryDecisionRequest body) {
		return entries.decide(id, false, body.note());
	}

	@PreAuthorize(APPROVE)
	@RequiresStepUp
	@PostMapping("/entries/{id}/reverse")
	EntryView reverse(@PathVariable long id, @Valid @RequestBody EntryReverseRequest body) {
		return entries.reverse(id, body.reason());
	}

	// ------------------------------------------------------------ books

	@PreAuthorize(VIEW)
	@GetMapping("/ledger/{accountId}")
	AccountLedger ledger(@PathVariable long accountId, @RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(defaultValue = "DETAILED") String view) {
		return books.ledger(accountId, from, to, view);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/days/{accountId}")
	DaySummary day(@PathVariable long accountId, @RequestParam LocalDate date) {
		return books.daySummary(accountId, date);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/days/{accountId}/closes")
	List<DayCloseView> closes(@PathVariable long accountId) {
		return books.dayCloses(accountId);
	}

	@PreAuthorize(CLOSE)
	@RequiresStepUp
	@PostMapping("/days/{accountId}/close")
	DaySummary closeDay(@PathVariable long accountId, @Valid @RequestBody CloseRequest body) {
		return books.closeDay(accountId, body.date(), body.counted(), body.note());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/reconciliation/{accountId}")
	List<LedgerLine> uncleared(@PathVariable long accountId) {
		return books.uncleared(accountId);
	}

	@PreAuthorize(RECONCILE)
	@RequiresStepUp
	@PostMapping("/reconciliation/{accountId}")
	Map<String, Integer> clear(@PathVariable long accountId, @Valid @RequestBody ClearRequest body) {
		return Map.of("changed", books.markCleared(accountId, body.lineIds(), body.clearedOn(), body.cleared()));
	}

	// ---------------------------------------------------------- reports

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/trial-balance")
	List<BalanceRow> trialBalance(@RequestParam LocalDate asOf) {
		return books.trialBalance(asOf);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/balance-sheet")
	BalanceSheet balanceSheet(@RequestParam LocalDate asOf) {
		return books.balanceSheet(asOf);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/monthly")
	List<MonthRow> monthly(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return books.monthlySummary(from, to);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/by-category")
	List<CategoryRow> byCategory(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return books.byCategory(from, to);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/daily-collection")
	List<Map<String, Object>> dailyCollection(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return books.dailyCollection(from, to);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/activity")
	PageResponse<ActivityRow> activity(@RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return books.activity(from, to, PageResponse.page(page), PageResponse.size(size));
	}
}
