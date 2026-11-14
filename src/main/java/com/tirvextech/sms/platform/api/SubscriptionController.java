package com.tirvextech.sms.platform.api;

import com.tirvextech.sms.platform.app.SubscriptionService;
import com.tirvextech.sms.platform.app.SubscriptionService.BillingOverview;
import com.tirvextech.sms.platform.app.SubscriptionService.InvoiceView;
import com.tirvextech.sms.platform.app.SubscriptionService.OwnSubscription;
import com.tirvextech.sms.platform.app.SubscriptionService.SchoolBilling;
import com.tirvextech.sms.platform.app.SubscriptionService.SubscriptionInput;
import com.tirvextech.sms.shared.security.AnyAuthenticated;
import com.tirvextech.sms.shared.security.CurrentActor;
import com.tirvextech.sms.shared.security.Permissions;
import com.tirvextech.sms.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Tirvex Tech subscriptions: the owner manages plans and invoices; a school sees its own (P-10). */
@RestController
class SubscriptionController {

	private static final String SUPER_ADMIN = "hasAuthority('" + CurrentActor.SUPER_ADMIN_AUTHORITY + "')";

	record SubscriptionRequest(@NotBlank String cycle, @NotBlank String amount, LocalDate startOn, Integer graceDays,
			@Size(max = 500) String notes) {
	}

	record InvoicePaymentRequest(LocalDate paidOn, @Size(max = 30) String method, @Size(max = 100) String reference) {
	}

	record InvoiceVoidRequest(@NotBlank @Size(max = 500) String reason) {
	}

	record PaymentDetailsRequest(@Size(max = 1000) String paymentDetails) {
	}

	record BillingRunResult(int invoicesIssued, int remindersSent) {
	}

	private final SubscriptionService billing;

	private final com.tirvextech.sms.platform.app.OwnerDashboard dashboard;

	SubscriptionController(SubscriptionService billing, com.tirvextech.sms.platform.app.OwnerDashboard dashboard) {
		this.billing = billing;
		this.dashboard = dashboard;
	}

	/** The owner's business view: paying schools, trials, school health, growth. */
	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/api/v1/platform/dashboard")
	com.tirvextech.sms.platform.app.OwnerDashboard.BusinessDashboard dashboard() {
		return dashboard.dashboard();
	}

	// ---------------------------------------------------------------- owner

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/api/v1/platform/billing")
	BillingOverview overview() {
		return billing.overview();
	}

	@PreAuthorize(SUPER_ADMIN)
	@PutMapping("/api/v1/platform/billing/details")
	PaymentDetailsRequest paymentDetails(@Valid @RequestBody PaymentDetailsRequest body) {
		return new PaymentDetailsRequest(billing.setPaymentDetails(body.paymentDetails()));
	}

	/** Issues due invoices and sends reminders now (it also runs every morning by itself). */
	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/api/v1/platform/billing/run")
	BillingRunResult run() {
		var today = billing.today();
		return new BillingRunResult(billing.issueDue(today, null), billing.sendNotices(today));
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/api/v1/platform/schools/{id}/billing")
	SchoolBilling schoolBilling(@PathVariable long id) {
		return billing.schoolBilling(id);
	}

	/** Starts (ending a free trial) or changes the school's monthly or yearly subscription. */
	@PreAuthorize(SUPER_ADMIN)
	@PutMapping("/api/v1/platform/schools/{id}/subscription")
	SchoolBilling saveSubscription(@PathVariable long id, @Valid @RequestBody SubscriptionRequest body) {
		return billing.saveSubscription(id,
				new SubscriptionInput(body.cycle(), body.amount(), body.startOn(), body.graceDays(), body.notes()));
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/api/v1/platform/schools/{id}/subscription/cancel")
	SchoolBilling cancel(@PathVariable long id) {
		return billing.cancel(id);
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/api/v1/platform/invoices/{id}/pay")
	InvoiceView pay(@PathVariable long id, @Valid @RequestBody InvoicePaymentRequest body) {
		return billing.markPaid(id, body.paidOn(), body.method(), body.reference());
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/api/v1/platform/invoices/{id}/void")
	InvoiceView voidInvoice(@PathVariable long id, @Valid @RequestBody InvoiceVoidRequest body) {
		return billing.voidInvoice(id, body.reason());
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping(value = "/api/v1/platform/invoices/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> pdf(@PathVariable long id) {
		return pdfResponse(billing.pdf(id));
	}

	// ---------------------------------------------------------------- the school's own

	/** The school's subscription: everyone sees whether payment is due; administrators also see the invoices. */
	@AnyAuthenticated
	@GetMapping("/api/v1/subscription")
	OwnSubscription own() {
		Long schoolId = CurrentActor.require().tenantSchoolId();
		if (schoolId == null) {
			throw ApiException.notFound("School");
		}
		return billing.own(schoolId, CurrentActor.hasPermission(Permissions.SCHOOL_PROFILE_EDIT) || CurrentActor.isSuperAdmin());
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_PROFILE_EDIT + "')")
	@GetMapping(value = "/api/v1/subscription/invoices/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> ownPdf(@PathVariable long id) {
		Long schoolId = CurrentActor.require().tenantSchoolId();
		billing.ownInvoice(schoolId == null ? -1 : schoolId, id);
		return pdfResponse(billing.pdf(id));
	}

	private static ResponseEntity<byte[]> pdfResponse(byte[] pdf) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("invoice.pdf").build().toString())
			.body(pdf);
	}
}
