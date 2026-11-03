package com.selectcode.sms.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Fees: set-up, bill runs, discounts, payments, credit, late fees, corrections, reversals (QA-FEE). */
@AutoConfigureMockMvc
class FeesIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate app;

	@Autowired
	DevOutbox devOutbox;

	@Autowired
	MessageOutbox messageOutbox;

	SeededSchool school;

	ApiClient admin;

	ApiClient accountant;

	long yearId;

	long class5;

	long sec5a;

	long class6;

	long sec6a;

	long tuition;

	long admission;

	LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Karachi"));

	String month = YearMonth.from(LocalDate.now(java.time.ZoneId.of("Asia/Karachi"))).toString();

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("FEE");
		TestData.admin(school, "admin", "admin-password-1");
		TestData.userWithPermissions(school, "cashier", "cashier-password-1", Permissions.FEES_VIEW,
				Permissions.FEES_SETUP, Permissions.FEES_DISCOUNT_MANAGE, Permissions.FEES_BILL_GENERATE,
				Permissions.FEES_PAYMENT_ADD, Permissions.FEES_ADJUST_REQUEST, Permissions.FEES_REPORTS_VIEW,
				Permissions.STUDENTS_VIEW);
		yearId = TestData.yearAroundToday(school, true);
		class5 = TestData.schoolClass(school, "Class 5", 5);
		sec5a = TestData.section(school, class5, "A");
		class6 = TestData.schoolClass(school, "Class 6", 6);
		sec6a = TestData.section(school, class6, "A");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		accountant = new ApiClient(mvc);
		accountant.login(school.code(), "cashier", "cashier-password-1").andExpect(status().isOk());
		tuition = head("Tuition fee", "MONTHLY");
		admission = head("Admission fee", "ONE_TIME");
		amounts(class5, tuition, "3500");
		amounts(class5, admission, "5000");
		amounts(class6, admission, "5000");
		devOutbox.clear();
	}

	// ----------------------------------------------------------------- helpers

	long head(String name, String frequency) throws Exception {
		var r = admin.post("/api/v1/fees/heads", """
				{"name":"%s","frequency":"%s","refundable":false}""".formatted(name, frequency))
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	void amounts(long classId, long headId, String amount) throws Exception {
		admin.put("/api/v1/fees/years/" + yearId + "/class-amounts", """
				[{"classId":%d,"feeHeadId":%d,"amount":"%s"}]""".formatted(classId, headId, amount))
			.andExpect(status().isOk());
	}

	long admit(String name, long sectionId, Long familyId) throws Exception {
		String family = familyId == null
				? "\"newFamily\":{\"fatherName\":\"Father of %s\",\"fatherPhone\":\"0300-%07d\"}".formatted(name,
						Math.abs(name.hashCode()) % 10_000_000)
				: "\"familyId\":" + familyId;
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"%s","gender":"MALE"},%s,"sectionId":%d,"admissionDate":"%s"}"""
			.formatted(name, family, sectionId, today.minusDays(25))).andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	long familyOf(long studentId) throws Exception {
		return ((Number) ApiClient.json(admin.get("/api/v1/students/" + studentId), "$.family.id")).longValue();
	}

	ResultActions run(ApiClient client, String body, String key) throws Exception {
		return client.postWithKey("/api/v1/fees/bill-runs", body, key);
	}

	String bills(String period, long... headIds) {
		StringBuilder heads = new StringBuilder();
		for (long h : headIds) {
			heads.append(heads.isEmpty() ? "" : ",").append(h);
		}
		return """
				{"period":"%s","headIds":[%s],"issueDate":"%s"}""".formatted(period, heads, today);
	}

	long pay(ApiClient client, long familyId, String amount, String key) throws Exception {
		var r = client.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"%s","method":"CASH","accountId":%d}""".formatted(familyId, amount, cash()), key)
			.andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	long cash() {
		return TestDatabase.superuser().queryForObject(
				"select id from ledger_account where school_id = ? and subtype = 'CASH'", Long.class, school.id());
	}

	BigDecimal studentBalance(long studentId) throws Exception {
		return new BigDecimal((String) ApiClient.json(admin.get("/api/v1/fees/students/" + studentId + "/ledger"),
				"$.owed"));
	}

	String next(int months) {
		return YearMonth.parse(month).plusMonths(months).toString();
	}

	/** The accounting invariants every sequence of fee operations must keep (QA-FEE-027, plan §Phase 3 exit). */
	void assertReconciles() {
		JdbcTemplate db = TestDatabase.superuser();
		long s = school.id();
		BigDecimal payments = nz(db.queryForObject("select sum(amount) from payment where school_id = ?",
				BigDecimal.class, s));
		BigDecimal allocations = nz(db.queryForObject("select sum(amount) from payment_allocation where school_id = ?",
				BigDecimal.class, s));
		BigDecimal credit = nz(db.queryForObject("select sum(remaining) from payment_credit where school_id = ?",
				BigDecimal.class, s));
		assertThat(payments).as("payments = allocations + unused credit").isEqualByComparingTo(allocations.add(credit));
		BigDecimal income = nz(db.queryForObject("""
				select sum(l.credit - l.debit) from journal_line l join ledger_account a on a.id = l.ledger_account_id
				where l.school_id = ? and a.subtype = 'FEE_INCOME'""", BigDecimal.class, s));
		assertThat(income).as("fee income = allocations").isEqualByComparingTo(allocations);
		BigDecimal money = nz(db.queryForObject("""
				select sum(l.debit - l.credit) from journal_line l join ledger_account a on a.id = l.ledger_account_id
				where l.school_id = ? and a.subtype in ('CASH', 'BANK')""", BigDecimal.class, s));
		assertThat(money).as("cash and bank = payments").isEqualByComparingTo(payments);
		BigDecimal advances = nz(db.queryForObject("""
				select sum(l.credit - l.debit) from journal_line l join ledger_account a on a.id = l.ledger_account_id
				where l.school_id = ? and a.subtype = 'STUDENT_ADVANCE'""", BigDecimal.class, s));
		assertThat(advances).as("advances account = unused credit").isEqualByComparingTo(credit);
		Long overpaid = db.queryForObject("select count(*) from fee_line_status where school_id = ? and balance < 0",
				Long.class, s);
		assertThat(overpaid).as("no line is paid beyond its charge").isZero();
		Long unbalanced = db.queryForObject("""
				select count(*) from (select entry_id from journal_line where school_id = ?
				group by entry_id having sum(debit) <> sum(credit)) x""", Long.class, s);
		assertThat(unbalanced).isZero();
	}

	static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	// ------------------------------------------------------------------- tests

	@Test
	void billRunsAreIdempotentAndFlagMissingAmounts() throws Exception {
		long ali = admit("Ali", sec5a, null);
		long sara = admit("Sara", sec6a, null);
		// QA-FEE-001: class 6 has no tuition amount
		admin.get("/api/v1/fees/years/" + yearId + "/missing-amounts")
			.andExpect(jsonPath("$[?(@.classId==" + class6 + " && @.feeHeadId==" + tuition + ")]").exists());
		var first = run(admin, bills(month, tuition, admission), "run-1");
		first.andExpect(status().isOk())
			.andExpect(jsonPath("$.billsCreated").value(2))
			.andExpect(jsonPath("$.linesCreated").value(3))
			.andExpect(jsonPath("$.missing[0].studentId").value(sara))
			.andExpect(jsonPath("$.totalBilled").value("13500.00"));
		// QA-FEE-006: same key → same report; new key → nothing new
		run(admin, bills(month, tuition, admission), "run-1").andExpect(jsonPath("$.replayed").value(true))
			.andExpect(jsonPath("$.billsCreated").value(2));
		run(admin, bills(month, tuition, admission), "run-2").andExpect(jsonPath("$.billsCreated").value(0))
			.andExpect(jsonPath("$.alreadyBilled").value(3));
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from bill_line where school_id = ?",
				Long.class, school.id())).isEqualTo(3);
		// One-time admission fee is never charged twice, even in another month's run.
		run(admin, bills(next(1), tuition, admission), "run-3").andExpect(jsonPath("$.linesCreated").value(1));
		assertThat(studentBalance(ali)).isEqualByComparingTo("12000.00");
		// No key → rejected
		admin.postWithKey("/api/v1/fees/bill-runs", bills(month, tuition), null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void discountsAboveTheLimitWaitForApprovalAndRoundOnce() throws Exception {
		long ali = admit("Ali", sec5a, null);
		// QA-FEE-003: cashier gives 50% (limit 25%) → pending, not on the bill
		var d = accountant.post("/api/v1/fees/discounts", """
				{"studentId":%d,"feeHeadId":%d,"kind":"PERCENT","value":"50","reason":"Staff child"}"""
			.formatted(ali, tuition)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
		long discountId = ((Number) ApiClient.json(d, "$.id")).longValue();
		run(accountant, bills(month, tuition), "d-1").andExpect(jsonPath("$.totalBilled").value("3500.00"));
		accountant.post("/api/v1/fees/discounts/" + discountId + "/approve").andExpect(status().isForbidden());
		admin.post("/api/v1/fees/discounts/" + discountId + "/approve").andExpect(jsonPath("$.status").value("APPROVED"));
		// QA-FEE-002: approved 50% of 3,500 = 1,750.00
		run(accountant, bills(next(1), tuition), "d-2").andExpect(jsonPath("$.totalBilled").value("1750.00"));
		// QA-FEE-028: 33.33% of 3,000 → discount 999.90, net 2,000.10
		long lab = head("Lab charges", "YEARLY");
		amounts(class5, lab, "3000");
		admin.post("/api/v1/fees/discounts", """
				{"studentId":%d,"feeHeadId":%d,"kind":"PERCENT","value":"33.33","reason":"Scholarship"}"""
			.formatted(ali, lab)).andExpect(jsonPath("$.status").value("APPROVED"));
		run(admin, bills(month, lab), "d-3").andExpect(jsonPath("$.totalBilled").value("2000.10"));
		assertThat(TestDatabase.superuser().queryForObject(
				"select discount from bill_line where school_id = ? and fee_head_id = ?", BigDecimal.class, school.id(),
				lab)).isEqualByComparingTo("999.90");
	}

	@Test
	void siblingRulesDiscountLaterChildren() throws Exception {
		// QA-FEE-004
		admin.put("/api/v1/fees/sibling-rules", """
				[{"childOrder":2,"percent":"10","feeHeadIds":[%d]},{"childOrder":3,"percent":"20","feeHeadIds":[%d]}]"""
			.formatted(tuition, tuition)).andExpect(status().isOk());
		long first = admit("Hamza", sec5a, null);
		long family = familyOf(first);
		long second = admit("Hira", sec5a, family);
		long third = admit("Hadi", sec5a, family);
		run(admin, bills(month, tuition), "s-1").andExpect(jsonPath("$.totalBilled").value("9450.00"));
		assertThat(studentBalance(first)).isEqualByComparingTo("3500.00");
		assertThat(studentBalance(second)).isEqualByComparingTo("3150.00");
		assertThat(studentBalance(third)).isEqualByComparingTo("2800.00");
		admin.get("/api/v1/fees/reports/missing-sibling-discounts").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void partialPaymentIssuesReceiptPostsJournalAndIsIdempotent() throws Exception {
		long transport = head("Transport", "MONTHLY");
		amounts(class5, transport, "3500");
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition, transport), "p-run").andExpect(jsonPath("$.totalBilled").value("7000.00"));
		String body = """
				{"familyId":%d,"amount":"5000","method":"CASH","accountId":%d}""".formatted(family, cash());
		// QA-FEE-010
		var r = accountant.postWithKey("/api/v1/fees/payments", body, "pay-1").andExpect(status().isCreated())
			.andExpect(jsonPath("$.receiptNo").value(org.hamcrest.Matchers.endsWith("/00001")))
			.andExpect(jsonPath("$.familyBalance").value("2000.00"))
			.andExpect(jsonPath("$.allocations.length()").value(2));
		long paymentId = ((Number) ApiClient.json(r, "$.id")).longValue();
		admin.get("/api/v1/fees/bills?studentId=" + ali).andExpect(jsonPath("$.items[0].status").value("PARTIAL"))
			.andExpect(jsonPath("$.items[0].balance").value("2000.00"));
		// QA-FEE-012: retry with the same key → the same payment, not a second one
		accountant.postWithKey("/api/v1/fees/payments", body, "pay-1").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(paymentId)).andExpect(jsonPath("$.replayed").value(true));
		accountant.postWithKey("/api/v1/fees/payments", body.replace("5000", "4000"), "pay-1")
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		accountant.postWithKey("/api/v1/fees/payments", body, null)
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from payment where school_id = ?",
				Long.class, school.id())).isEqualTo(1);
		// QA-FEE-026: the payment can be traced to account, method, user and heads
		admin.get("/api/v1/fees/payments/" + paymentId).andExpect(jsonPath("$.accountName").value("Cash in hand"))
			.andExpect(jsonPath("$.receivedBy").value("User cashier"))
			.andExpect(jsonPath("$.journalEntryId").isNumber())
			.andExpect(jsonPath("$.allocations[0].headName").value("Tuition fee"));
		// QA-FEE-015: receipt SMS is queued in the same transaction and sent afterwards
		assertThat(TestDatabase.superuser().queryForObject(
				"select status from message_outbox where school_id = ? and source_id = ?", String.class, school.id(),
				paymentId)).isEqualTo("PENDING");
		messageOutbox.dispatchDue();
		assertThat(TestDatabase.superuser().queryForObject(
				"select status from message_outbox where school_id = ? and source_id = ?", String.class, school.id(),
				paymentId)).isEqualTo("SENT");
		assertThat(devOutbox.entries()).anySatisfy(e -> assertThat(e.message().body()).contains("Receipt")
			.contains("Balance due Rs. 2,000"));
		byte[] receipt = admin.get("/api/v1/fees/payments/" + paymentId + "/receipt").andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(receipt, 0, 4)).isEqualTo("%PDF");
		assertReconciles();
	}

	@Test
	void twoCashiersCannotOverpayTheSameBill() throws Exception {
		// QA-FEE-013
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition), "c-run");
		ApiClient second = new ApiClient(mvc);
		second.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		Callable<Long> a = () -> pay(admin, family, "3500", UUID.randomUUID().toString());
		Callable<Long> b = () -> pay(second, family, "3500", UUID.randomUUID().toString());
		try (var pool = Executors.newFixedThreadPool(2)) {
			Future<Long> fa = pool.submit(a);
			Future<Long> fb = pool.submit(b);
			fa.get();
			fb.get();
		}
		assertThat(studentBalance(ali)).isEqualByComparingTo("0.00");
		admin.get("/api/v1/fees/families/" + family + "/ledger").andExpect(jsonPath("$.credit").value("3500.00"))
			.andExpect(jsonPath("$.balance").value("-3500.00"));
		assertReconciles();
	}

	@Test
	void advancePaymentSettlesLaterBills() throws Exception {
		// QA-FEE-017
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		pay(admin, family, "7000", "adv-1");
		run(admin, bills(month, tuition), "adv-run-1").andExpect(jsonPath("$.creditApplied").value("3500.00"));
		run(admin, bills(next(1), tuition), "adv-run-2").andExpect(jsonPath("$.creditApplied").value("3500.00"));
		admin.get("/api/v1/fees/bills?studentId=" + ali).andExpect(jsonPath("$.items[0].status").value("PAID"))
			.andExpect(jsonPath("$.items[1].status").value("PAID"));
		admin.get("/api/v1/fees/families/" + family + "/ledger").andExpect(jsonPath("$.balance").value("0.00"));
		run(admin, bills(next(2), tuition), "adv-run-3").andExpect(jsonPath("$.creditApplied").value("0.00"));
		assertThat(studentBalance(ali)).isEqualByComparingTo("3500.00");
		assertReconciles();
	}

	@Test
	void familyPaysForThreeChildrenOnOneReceipt() throws Exception {
		// QA-FEE-016
		long a = admit("Zain", sec5a, null);
		long family = familyOf(a);
		long b = admit("Zara", sec5a, family);
		long c = admit("Zoya", sec5a, family);
		run(admin, bills(month, tuition), "f-run");
		var r = admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"10500","method":"BANK","accountId":%d,"reference":"DEP-123"}"""
			.formatted(family, cash()), "f-pay").andExpect(status().isCreated());
		List<Integer> students = ApiClient.json(r, "$.allocations[*].studentId");
		assertThat(students).hasSize(3);
		for (long s : List.of(a, b, c)) {
			assertThat(studentBalance(s)).isEqualByComparingTo("0.00");
		}
		assertReconciles();
	}

	@Test
	void lateFeeIsAddedOnceToOverdueUnpaidBills() throws Exception {
		// QA-FEE-019
		long ali = admit("Ali", sec5a, null);
		long sara = admit("Sara", sec6a, null);
		String overdue = """
				{"period":"%s","headIds":[%d],"issueDate":"%s","dueDate":"%s"}"""
			.formatted(month, admission, today.minusDays(20), today.minusDays(10));
		run(admin, overdue, "late-run");
		String late = """
				{"dueOnOrBefore":"%s","amount":"200","excludeClassIds":[%d]}""".formatted(today.minusDays(1), class6);
		admin.postWithKey("/api/v1/fees/late-fee-runs", late, "late-1").andExpect(jsonPath("$.linesCreated").value(1));
		admin.postWithKey("/api/v1/fees/late-fee-runs", late, "late-2").andExpect(jsonPath("$.linesCreated").value(0))
			.andExpect(jsonPath("$.alreadyBilled").value(1));
		assertThat(studentBalance(ali)).isEqualByComparingTo("5200.00");
		assertThat(studentBalance(sara)).isEqualByComparingTo("5000.00");
		// Defaulters and the final reminder letter (QA-FEE-024)
		long family = familyOf(ali);
		admin.get("/api/v1/fees/defaulters?minBalance=5100").andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].familyId").value(family));
		byte[] letter = admin.post("/api/v1/fees/families/" + family + "/final-reminder").andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(letter, 0, 4)).isEqualTo("%PDF");
		admin.get("/api/v1/fees/families/" + family + "/follow-ups").andExpect(jsonPath("$.length()").value(1));
		admin.postWithKey("/api/v1/fees/reminders", "{\"familyIds\":[%d]}".formatted(family), "rem-1")
			.andExpect(jsonPath("$.queued").value(1));
		admin.postWithKey("/api/v1/fees/reminders", "{\"familyIds\":[%d]}".formatted(family), "rem-1")
			.andExpect(jsonPath("$.queued").value(0)).andExpect(jsonPath("$.alreadyQueued").value(1));
	}

	@Test
	void correctionsTakeEffectOnlyWhenApproved() throws Exception {
		// QA-FEE-021
		long ali = admit("Ali", sec5a, null);
		run(admin, bills(month, tuition), "adj-run");
		long lineId = TestDatabase.superuser().queryForObject(
				"select id from bill_line where school_id = ? and student_id = ?", Long.class, school.id(), ali);
		var r = accountant.post("/api/v1/fees/adjustments", """
				{"billLineId":%d,"amount":"-500","kind":"CORRECTION","reason":"Wrong amount charged"}""".formatted(lineId))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
		long adjId = ((Number) ApiClient.json(r, "$.id")).longValue();
		assertThat(studentBalance(ali)).isEqualByComparingTo("3500.00");
		admin.post("/api/v1/fees/adjustments/" + adjId + "/approve").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
		admin.stepUp(devOutbox);
		admin.post("/api/v1/fees/adjustments/" + adjId + "/approve").andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decidedBy").value("User admin"));
		assertThat(studentBalance(ali)).isEqualByComparingTo("3000.00");
		assertThat(TestDatabase.superuser().queryForObject("select net from bill_line where id = ?", BigDecimal.class,
				lineId)).as("the original charge is unchanged").isEqualByComparingTo("3500.00");
		accountant.post("/api/v1/fees/adjustments", """
				{"billLineId":%d,"amount":"-4000","kind":"WAIVER","reason":"Too much"}""".formatted(lineId))
			.andExpect(jsonPath("$.code").value("BELOW_ZERO"));
		// Bad debt write-off appears in the report
		admin.post("/api/v1/fees/adjustments", """
				{"billLineId":%d,"amount":"-3000","kind":"BAD_DEBT","reason":"Family moved abroad"}""".formatted(lineId))
			.andExpect(jsonPath("$.status").value("APPROVED"));
		admin.get("/api/v1/fees/reports/write-offs").andExpect(jsonPath("$[0].amount").value("-3000.00"));
		assertThat(studentBalance(ali)).isEqualByComparingTo("0.00");
	}

	@Test
	void reducingAPaidChargeTurnsTheExcessIntoCredit() throws Exception {
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition), "x-run");
		pay(admin, family, "3500", "x-pay");
		long lineId = TestDatabase.superuser().queryForObject(
				"select id from bill_line where school_id = ? and student_id = ?", Long.class, school.id(), ali);
		admin.post("/api/v1/fees/adjustments", """
				{"billLineId":%d,"amount":"-500","kind":"CORRECTION","reason":"Charged too much"}""".formatted(lineId))
			.andExpect(jsonPath("$.status").value("APPROVED"));
		admin.get("/api/v1/fees/families/" + family + "/ledger").andExpect(jsonPath("$.credit").value("500.00"))
			.andExpect(jsonPath("$.owed").value("0.00"));
		run(admin, bills(next(1), tuition), "x-run-2").andExpect(jsonPath("$.creditApplied").value("500.00"));
		assertThat(studentBalance(ali)).isEqualByComparingTo("3000.00");
		assertReconciles();
	}

	@Test
	void reversalRestoresTheBalanceAndKeepsTheOriginal() throws Exception {
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition), "r-run");
		long payment = pay(admin, family, "3500", "r-pay");
		assertThat(studentBalance(ali)).isEqualByComparingTo("0.00");
		admin.post("/api/v1/fees/payments/" + payment + "/reverse", "{\"reason\":\"Cheque bounced\"}")
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
		admin.stepUp(devOutbox);
		admin.post("/api/v1/fees/payments/" + payment + "/reverse", "{\"reason\":\"Cheque bounced\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.reversedByPaymentId").isNumber());
		admin.post("/api/v1/fees/payments/" + payment + "/reverse", "{\"reason\":\"Again\"}")
			.andExpect(status().isConflict());
		assertThat(studentBalance(ali)).isEqualByComparingTo("3500.00");
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from payment where school_id = ?",
				Long.class, school.id())).isEqualTo(2);
		assertReconciles();
	}

	@Test
	void moneyTablesAreInsertOnlyForTheApp() {
		// QA-FEE-022: no endpoint edits payments, and the database refuses it too
		TenantContext.runAsSchool(school.id(), () -> {
			assertThatThrownBy(() -> app.update("update payment set amount = 1")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from bill_line")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("update payment_allocation set amount = 1")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from journal_line")).rootCause()
				.hasMessageContaining("permission denied");
		});
	}

	@Test
	void anotherSchoolCannotSeeFees() throws Exception {
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition), "t-run");
		long payment = pay(admin, family, "1000", "t-pay");
		SeededSchool other = TestData.school("FEB");
		TestData.admin(other, "admin", "admin-password-2");
		ApiClient intruder = new ApiClient(mvc);
		intruder.login(other.code(), "admin", "admin-password-2").andExpect(status().isOk());
		intruder.get("/api/v1/fees/payments/" + payment).andExpect(status().isNotFound());
		intruder.get("/api/v1/fees/payments/" + payment + "/receipt").andExpect(status().isNotFound());
		intruder.get("/api/v1/fees/students/" + ali + "/ledger").andExpect(status().isNotFound());
		intruder.get("/api/v1/fees/payments").andExpect(jsonPath("$.totalItems").value(0));
		intruder.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"10","method":"CASH","accountId":%d}""".formatted(family, cash()), "x")
			.andExpect(status().isNotFound());
	}

	@Test
	void aFailureMidPaymentLeavesNothingBehind() throws Exception {
		// QA-FEE-011: the journal cannot be posted (no fee income account) after the receipt was numbered
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		run(admin, bills(month, tuition), "atomic-run");
		TestDatabase.superuser().update(
				"update ledger_account set status = 'INACTIVE' where school_id = ? and subtype = 'FEE_INCOME'", school.id());
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"1000","method":"CASH","accountId":%d}""".formatted(family, cash()), "atomic-1")
			.andExpect(status().isInternalServerError());
		JdbcTemplate db = TestDatabase.superuser();
		assertThat(db.queryForObject("select count(*) from payment where school_id = ?", Long.class, school.id())).isZero();
		assertThat(db.queryForObject("select count(*) from payment_allocation where school_id = ?", Long.class,
				school.id())).isZero();
		assertThat(db.queryForObject("select count(*) from message_outbox where school_id = ?", Long.class,
				school.id())).isZero();
		db.update("update ledger_account set status = 'ACTIVE' where school_id = ? and subtype = 'FEE_INCOME'",
				school.id());
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"1000","method":"CASH","accountId":%d}""".formatted(family, cash()), "atomic-1")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.receiptNo").value(org.hamcrest.Matchers.endsWith("/00001")));
		assertReconciles();
	}

	@Test
	void feesNeedTheAllClassesScope() throws Exception {
		// QA-FEE-032: a teacher-scoped user with fee permissions still cannot read fees
		long userId = TestData.userWithPermissions(school, "teacher", "teacher-password-1", Permissions.FEES_VIEW,
				Permissions.FEES_PAYMENT_ADD);
		TestDatabase.superuser().update("update app_user set data_scope = 'ASSIGNED_CLASSES' where id = ?", userId);
		ApiClient teacher = new ApiClient(mvc);
		teacher.login(school.code(), "teacher", "teacher-password-1").andExpect(status().isOk());
		teacher.get("/api/v1/fees/payments").andExpect(status().isForbidden());
		teacher.get("/api/v1/fees/heads").andExpect(status().isForbidden());
		accountant.get("/api/v1/fees/heads").andExpect(status().isOk());
	}

	@Test
	void receiptNumbersAreSequentialWithoutGaps() throws Exception {
		// QA-FEE-014
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		for (int i = 0; i < 12; i++) {
			pay(admin, family, "100", "seq-" + i);
		}
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"-5","method":"CASH","accountId":%d}""".formatted(family, cash()), "seq-bad")
			.andExpect(status().isUnprocessableContent());
		List<String> receipts = TestDatabase.superuser().queryForList(
				"select receipt_no from payment where school_id = ? order by id", String.class, school.id());
		for (int i = 0; i < receipts.size(); i++) {
			assertThat(receipts.get(i)).endsWith("/" + String.format("%05d", i + 1));
		}
	}

	@Test
	void randomSequencesAlwaysReconcile() throws Exception {
		// QA-FEE-027: property-style check over random bills, payments, corrections and reversals
		Random random = new Random(42);
		long a = admit("Rand A", sec5a, null);
		long family = familyOf(a);
		admit("Rand B", sec5a, family);
		admin.stepUp(devOutbox);
		List<Long> payments = new ArrayList<>();
		int months = 0;
		for (int step = 0; step < 40; step++) {
			switch (random.nextInt(4)) {
				case 0 -> run(admin, bills(next(months++ % 10), tuition), UUID.randomUUID().toString());
				case 1 -> payments.add(pay(admin, family, String.valueOf(100 + random.nextInt(5000)) + ".25",
						UUID.randomUUID().toString()));
				case 2 -> {
					if (!payments.isEmpty()) {
						long p = payments.remove(random.nextInt(payments.size()));
						admin.post("/api/v1/fees/payments/" + p + "/reverse", "{\"reason\":\"Random reversal\"}");
					}
				}
				default -> {
					List<Long> lines = TestDatabase.superuser().queryForList(
							"select bill_line_id from fee_line_status where school_id = ? and net + adjusted >= 100",
							Long.class, school.id());
					if (!lines.isEmpty()) {
						admin.post("/api/v1/fees/adjustments", """
								{"billLineId":%d,"amount":"-33.33","kind":"WAIVER","reason":"Random"}"""
							.formatted(lines.get(random.nextInt(lines.size()))));
					}
				}
			}
			assertReconciles();
		}
		admin.post("/api/v1/fees/families/" + family + "/apply-credit").andExpect(status().isOk());
		assertReconciles();
	}

	@Test
	void vouchersLedgerAndStatusViews() throws Exception {
		long ali = admit("Ali", sec5a, null);
		long family = familyOf(ali);
		var r = run(admin, bills(month, tuition, admission), "v-run");
		long runId = ((Number) ApiClient.json(r, "$.runId")).longValue();
		pay(admin, family, "1000", "v-pay");
		byte[] pdf = admin.post("/api/v1/fees/vouchers", "{\"runId\":%d}".formatted(runId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
		// QA-FEE-023: running balance in the ledger
		admin.get("/api/v1/fees/families/" + family + "/ledger").andExpect(jsonPath("$.entries.length()").value(3))
			.andExpect(jsonPath("$.entries[2].balance").value("7500.00"))
			.andExpect(jsonPath("$.owed").value("7500.00"));
		admin.get("/api/v1/fees/status?classId=" + class5).andExpect(jsonPath("$.items[0].status").value("PARTIAL"))
			.andExpect(jsonPath("$.items[0].charged").value("8500.00"));
		// QA-FEE-025: collections by head match fee income
		admin.get("/api/v1/fees/reports/collections?from=%s&to=%s".formatted(today, today))
			.andExpect(jsonPath("$[0].collected").value("1000.00"));
		admin.get("/api/v1/fees/students/" + ali + "/admission-proposal").andExpect(jsonPath("$.length()").value(0));
		assertReconciles();
	}
}
