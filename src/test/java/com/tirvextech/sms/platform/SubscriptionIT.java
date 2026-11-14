package com.tirvextech.sms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.platform.app.SubscriptionService;
import com.tirvextech.sms.shared.messaging.DevOutbox;
import com.tirvextech.sms.shared.security.Permissions;
import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Tirvex Tech subscriptions: monthly and yearly plans, invoices, reminders, pausing and restoring access. */
@AutoConfigureMockMvc
class SubscriptionIT extends IntegrationTest {

	static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Autowired
	SubscriptionService billing;

	ApiClient owner;

	TestData.SeededSchool school;

	LocalDate today;

	@BeforeEach
	void setUp() throws Exception {
		String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		TestData.superAdmin(email, "owner-password-1", SECRET);
		owner = new ApiClient(mvc);
		owner.platformLogin(email, "owner-password-1", SECRET).andExpect(status().isOk());
		school = TestData.school("BIL");
		TestData.admin(school, "admin", "admin-password-1");
		TestDatabase.superuser().update("update app_user set email = ? where school_id = ? and username = 'admin'",
				"head@" + school.code().toLowerCase() + ".test", school.id());
		TestDatabase.superuser().update("update app_user set is_main_admin = true where school_id = ? and username = 'admin'", school.id());
		today = billing.today();
		outbox.clear();
	}

	String plan(String cycle, String amount, LocalDate start) {
		return """
				{"cycle":"%s","amount":"%s","startOn":"%s","graceDays":10}""".formatted(cycle, amount, start);
	}

	long count(String sql, Object... args) {
		return TestDatabase.superuser().queryForObject(sql, Long.class, args);
	}

	ApiClient signIn() throws Exception {
		ApiClient admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		return admin;
	}

	@Test
	void aMonthlyPlanEndsTheTrialAndIssuesTheFirstInvoiceOnce() throws Exception {
		TestDatabase.superuser().update("update school set trial_ends_at = now() + interval '5 days' where id = ?", school.id());
		owner.put("/api/v1/platform/billing/details", "{\"paymentDetails\":\"Meezan Bank 0123-456789, Raast 0300-0000000\"}")
			.andExpect(status().isOk());
		var r = owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("MONTHLY", "5000", today))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.subscription.cycle").value("MONTHLY"))
			.andExpect(jsonPath("$.subscription.amount").value("5000.00"))
			.andExpect(jsonPath("$.subscription.nextDueOn").value(today.plusMonths(1).toString()))
			.andExpect(jsonPath("$.subscription.standing").value("DUE"))
			.andExpect(jsonPath("$.invoices.length()").value(1))
			.andExpect(jsonPath("$.invoices[0].periodStart").value(today.toString()))
			.andExpect(jsonPath("$.invoices[0].periodEnd").value(today.plusMonths(1).minusDays(1).toString()))
			.andExpect(jsonPath("$.invoices[0].dueOn").value(today.toString()));
		String number = ApiClient.json(r, "$.invoices[0].invoiceNo");
		assertThat(number).matches("INV-\\d{4}-\\d{4}");
		// The trial is over: full access now
		assertThat(count("select count(*) from school where id = ? and trial_ends_at is null", school.id())).isEqualTo(1);
		// The administrator got the invoice by email, with the payment details
		assertThat(outbox.entries()).anyMatch(e -> e.message().recipient().equals("head@" + school.code().toLowerCase() + ".test")
				&& e.message().subject().startsWith("Invoice " + number + " for ") && e.message().body().contains("Meezan Bank 0123-456789"));
		// Running again issues nothing new
		assertThat(billing.issueDue(today, school.id())).isZero();
		assertThat(count("select count(*) from platform_invoice where billed_school_id = ?", school.id())).isEqualTo(1);
		// A week before the next month starts, its invoice comes
		assertThat(billing.issueDue(today.plusMonths(1).minusDays(7), school.id())).isEqualTo(1);
		byte[] pdf = owner.get("/api/v1/platform/invoices/" + ApiClient.json(r, "$.invoices[0].id") + "/pdf")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
	}

	@Test
	void aYearlyPlanBillsAYearAndAFutureStartWaitsUntilAWeekBefore() throws Exception {
		LocalDate start = today.plusDays(30);
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("YEARLY", "50000", start))
			.andExpect(jsonPath("$.invoices.length()").value(0))
			.andExpect(jsonPath("$.subscription.standing").value("PAID_UP"));
		assertThat(billing.issueDue(start.minusDays(8), school.id())).isZero();
		assertThat(billing.issueDue(start.minusDays(7), school.id())).isEqualTo(1);
		owner.get("/api/v1/platform/schools/" + school.id() + "/billing")
			.andExpect(jsonPath("$.invoices[0].cycle").value("YEARLY"))
			.andExpect(jsonPath("$.invoices[0].periodEnd").value(start.plusYears(1).minusDays(1).toString()))
			.andExpect(jsonPath("$.invoices[0].amount").value("50000.00"))
			.andExpect(jsonPath("$.subscription.nextDueOn").value(start.plusYears(1).toString()));
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("WEEKLY", "1", start))
			.andExpect(jsonPath("$.code").value("CYCLE_INVALID"));
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("MONTHLY", "abc", start))
			.andExpect(jsonPath("$.code").value("AMOUNT_INVALID"));
	}

	@Test
	void anUnpaidInvoicePausesAccessAfterTheGraceDaysUntilItIsPaid() throws Exception {
		var r = owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("MONTHLY", "5000", today));
		long invoice = ((Number) ApiClient.json(r, "$.invoices[0].id")).longValue();
		ApiClient admin = signIn();
		admin.get("/api/v1/subscription").andExpect(jsonPath("$.standing").value("DUE"))
			.andExpect(jsonPath("$.openInvoices[0].amount").value("5000.00"));

		// 11 days late with 10 grace days
		TestDatabase.superuser().update("update platform_invoice set due_on = ? where id = ?", java.sql.Date.valueOf(today.minusDays(11)), invoice);
		admin.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ApiClient(mvc).login(school.code(), "admin", "admin-password-1")
			.andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_UNPAID"));
		owner.get("/api/v1/platform/schools/" + school.id() + "/billing")
			.andExpect(jsonPath("$.subscription.standing").value("PAUSED"))
			.andExpect(jsonPath("$.invoices[0].paused").value(true));
		// The due-date and paused emails go once each
		outbox.clear();
		billing.sendNotices(today);
		billing.sendNotices(today);
		String head = "head@" + school.code().toLowerCase() + ".test";
		assertThat(outbox.entries()).filteredOn(e -> e.message().recipient().equals(head) && e.message().subject().startsWith("Payment due today")).hasSize(1);
		assertThat(outbox.entries()).filteredOn(e -> e.message().recipient().equals(head) && e.message().subject().startsWith("Access paused")).hasSize(1);

		// Paid: access is back at once and a receipt goes by email
		owner.post("/api/v1/platform/invoices/" + invoice + "/pay", "{\"method\":\"Bank transfer\",\"reference\":\"TRX-1\"}")
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.paymentReference").value("TRX-1"));
		signIn().get("/api/v1/subscription").andExpect(jsonPath("$.standing").value("PAID_UP"));
		assertThat(outbox.entries()).anyMatch(e -> e.message().subject().startsWith("Payment received"));
		owner.post("/api/v1/platform/invoices/" + invoice + "/pay", "{}").andExpect(jsonPath("$.code").value("INVOICE_NOT_UNPAID"));
	}

	@Test
	void ownerTotalsCancellingAndVoiding() throws Exception {
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("MONTHLY", "7000", today));
		var o = owner.get("/api/v1/platform/billing").andExpect(status().isOk());
		java.util.List<String> unpaid = ApiClient.json(o, "$.subscriptions[?(@.schoolId==" + school.id() + ")].unpaidAmount");
		assertThat(unpaid).containsExactly("7000.00");
		assertThat(new java.math.BigDecimal(ApiClient.<String>json(o, "$.summary.unpaid"))).isGreaterThanOrEqualTo(new java.math.BigDecimal("7000"));

		long invoice = TestDatabase.superuser().queryForObject("select id from platform_invoice where billed_school_id = ?", Long.class, school.id());
		owner.post("/api/v1/platform/invoices/" + invoice + "/void", "{\"reason\":\"\"}").andExpect(status().isUnprocessableContent());
		owner.post("/api/v1/platform/invoices/" + invoice + "/void", "{\"reason\":\"Agreed free first month\"}")
			.andExpect(jsonPath("$.status").value("VOID"));
		owner.post("/api/v1/platform/invoices/" + invoice + "/pay", "{}").andExpect(jsonPath("$.code").value("INVOICE_NOT_UNPAID"));

		owner.post("/api/v1/platform/schools/" + school.id() + "/subscription/cancel").andExpect(jsonPath("$.subscription.status").value("CANCELLED"));
		assertThat(billing.issueDue(today.plusMonths(2), school.id())).isZero();
		// Restarting brings it back from the chosen date
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("YEARLY", "60000", today.plusDays(2)))
			.andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
			.andExpect(jsonPath("$.subscription.cycle").value("YEARLY"));
	}

	@Test
	void schoolStaffSeeWhatTheyMayAndNeverAnotherSchoolsInvoice() throws Exception {
		owner.put("/api/v1/platform/schools/" + school.id() + "/subscription", plan("MONTHLY", "5000", today));
		TestData.userWithPermissions(school, "teacher", "teacher-password-1", Permissions.STUDENTS_VIEW);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "teacher", "teacher-password-1").andExpect(status().isOk());
		teacher.get("/api/v1/subscription").andExpect(jsonPath("$.standing").value("DUE"))
			.andExpect(jsonPath("$.amount").doesNotExist())
			.andExpect(jsonPath("$.openInvoices[0].amount").doesNotExist())
			.andExpect(jsonPath("$.openInvoices[0].dueOn").value(today.toString()));

		var other = TestData.school("BIX");
		owner.put("/api/v1/platform/schools/" + other.id() + "/subscription", plan("MONTHLY", "3000", today));
		long theirs = TestDatabase.superuser().queryForObject("select id from platform_invoice where billed_school_id = ?", Long.class, other.id());
		ApiClient admin = signIn();
		admin.get("/api/v1/subscription/invoices/" + theirs + "/pdf").andExpect(status().isNotFound());
		teacher.get("/api/v1/platform/billing").andExpect(status().isForbidden());
	}
}
