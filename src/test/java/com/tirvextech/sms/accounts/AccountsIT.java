package com.selectcode.sms.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** School accounts: transfers, income/expense with approval, reversals, day close, reconciliation, reports (QA-ACC). */
@AutoConfigureMockMvc
class AccountsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Autowired
	JdbcTemplate app;

	SeededSchool school;

	ApiClient admin;

	ApiClient clerk;

	long cash;

	long bank;

	LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("ACC");
		TestData.admin(school, "admin", "admin-password-1");
		TestData.userWithPermissions(school, "clerk", "clerk-password-1", Permissions.ACCOUNTS_VIEW,
				Permissions.ACCOUNTS_MANAGE, Permissions.ACCOUNTS_ENTRY_ADD, Permissions.ACCOUNTS_DAY_CLOSE,
				Permissions.ACCOUNTS_RECONCILE, Permissions.ACCOUNTS_REPORTS_VIEW);
		TestData.yearAroundToday(school, true);
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.stepUp(outbox);
		clerk = new ApiClient(mvc);
		clerk.login(school.code(), "clerk", "clerk-password-1").andExpect(status().isOk());
		clerk.stepUp(outbox);
		cash = account("CASH");
		bank = account("BANK");
	}

	long account(String subtype) {
		return TestDatabase.superuser().queryForObject(
				"select id from ledger_account where school_id = ? and subtype = ? order by id limit 1", Long.class,
				school.id(), subtype);
	}

	long category(String name) {
		return TestDatabase.superuser().queryForObject(
				"select id from account_category where school_id = ? and name = ?", Long.class, school.id(), name);
	}

	ResultActions entry(ApiClient client, String json) throws Exception {
		return client.postWithKey("/api/v1/accounts/entries", json, UUID.randomUUID().toString());
	}

	BigDecimal balance(long accountId) throws Exception {
		List<String> balances = ApiClient.json(admin.get("/api/v1/accounts/money-accounts"),
				"$[?(@.id==" + accountId + ")].balance");
		return new BigDecimal(balances.getFirst());
	}

	void opening(long accountId, String amount) throws Exception {
		admin.post("/api/v1/accounts/money-accounts/" + accountId + "/opening-balance",
				"{\"amount\":\"%s\",\"date\":\"%s\"}".formatted(amount, today.minusDays(5))).andExpect(status().isOk());
	}

	void assertTrialBalanceNetsToZero() {
		JdbcTemplate db = TestDatabase.superuser();
		BigDecimal net = db.queryForObject("select coalesce(sum(debit - credit), 0) from journal_line where school_id = ?",
				BigDecimal.class, school.id());
		assertThat(net).isEqualByComparingTo("0");
	}

	@Test
	void transferMovesMoneyBetweenAccounts() throws Exception {
		// QA-ACC-001
		opening(cash, "300000");
		entry(clerk, """
				{"kind":"TRANSFER","accountId":%d,"toAccountId":%d,"amount":"200000","reference":"Deposit slip 77"}"""
			.formatted(cash, bank)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.entryNo").value("TRF-00001"));
		assertThat(balance(cash)).isEqualByComparingTo("100000");
		assertThat(balance(bank)).isEqualByComparingTo("200000");
		assertThat(TestDatabase.superuser().queryForObject("""
				select count(*) from journal_entry where school_id = ? and entry_type = 'TRANSFER'""", Long.class,
				school.id())).isEqualTo(1);
		entry(clerk, """
				{"kind":"TRANSFER","accountId":%d,"toAccountId":%d,"amount":"10"}""".formatted(cash, cash))
			.andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
		assertTrialBalanceNetsToZero();
	}

	@Test
	void largeExpensesWaitForApprovalAndReversalsKeepBothEntries() throws Exception {
		opening(cash, "100000");
		// QA-ACC-003: above the 50,000 limit → pending, nothing posted
		var pending = entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"60000","description":"Generator repair"}"""
			.formatted(category("Repairs and maintenance"), cash)).andExpect(jsonPath("$.status").value("PENDING"));
		long pendingId = ((Number) ApiClient.json(pending, "$.id")).longValue();
		assertThat(balance(cash)).isEqualByComparingTo("100000");
		clerk.post("/api/v1/accounts/entries/" + pendingId + "/approve").andExpect(status().isForbidden());
		admin.post("/api/v1/accounts/entries/" + pendingId + "/approve").andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decidedBy").value("User admin"));
		assertThat(balance(cash)).isEqualByComparingTo("40000");
		// Small expense: approved at once
		var small = entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"1500","description":"Chalk"}"""
			.formatted(category("Stationery and supplies"), cash)).andExpect(jsonPath("$.status").value("APPROVED"));
		long smallId = ((Number) ApiClient.json(small, "$.id")).longValue();
		// QA-ACC-006: entered twice by mistake → reversed, both visible
		clerk.post("/api/v1/accounts/entries/" + smallId + "/reverse", "{\"reason\":\"Entered twice\"}")
			.andExpect(status().isForbidden());
		admin.post("/api/v1/accounts/entries/" + smallId + "/reverse", "{\"reason\":\"Entered twice\"}")
			.andExpect(jsonPath("$.reversedByEntryId").isNumber());
		admin.post("/api/v1/accounts/entries/" + smallId + "/reverse", "{\"reason\":\"Again\"}")
			.andExpect(status().isConflict());
		assertThat(balance(cash)).isEqualByComparingTo("40000");
		admin.get("/api/v1/accounts/entries?kind=EXPENSE").andExpect(jsonPath("$.totalItems").value(3));
		// Salaries go through payroll, not an expense entry
		entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"10"}""".formatted(category("Salaries"), cash))
			.andExpect(jsonPath("$.code").value("USE_PAYROLL"));
		assertTrialBalanceNetsToZero();
	}

	@Test
	void entriesAreIdempotentAndInsertOnly() throws Exception {
		String body = """
				{"kind":"INCOME","categoryId":%d,"accountId":%d,"amount":"2500","description":"Old desks sold"}"""
			.formatted(category("Other income"), cash);
		long first = ((Number) ApiClient.json(clerk.postWithKey("/api/v1/accounts/entries", body, "inc-1"), "$.id")).longValue();
		clerk.postWithKey("/api/v1/accounts/entries", body, "inc-1").andExpect(jsonPath("$.id").value(first));
		clerk.postWithKey("/api/v1/accounts/entries", body.replace("2500", "3000"), "inc-1")
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		clerk.postWithKey("/api/v1/accounts/entries", body, null)
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		assertThat(balance(cash)).isEqualByComparingTo("2500");
		TenantContext.runAsSchool(school.id(), () -> {
			assertThatThrownBy(() -> app.update("update money_entry set amount = 1")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from money_entry_decision")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from day_close")).rootCause()
				.hasMessageContaining("permission denied");
		});
	}

	@Test
	void dayCloseLocksTheDayAndShowsFeeReceiptsOnce() throws Exception {
		opening(cash, "90000");
		// A fee receipt reaches the day summary through the journal, never typed again (QA-ACC-002)
		long sec = TestData.section(school, TestData.schoolClass(school, "Class 1", 1), "A");
		var student = admin.post("/api/v1/students", """
				{"student":{"name":"Day Student","gender":"MALE"},"newFamily":{"fatherName":"Father"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(sec, today.minusDays(3))).andExpect(status().isCreated());
		long familyId = ((Number) ApiClient.json(admin.get("/api/v1/students/" + ApiClient.json(student, "$.id")),
				"$.family.id")).longValue();
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"2500","method":"CASH","accountId":%d}""".formatted(familyId, cash), "fee-1")
			.andExpect(status().isCreated());
		admin.get("/api/v1/accounts/days/" + cash + "?date=" + today)
			.andExpect(jsonPath("$.opening").value("90000.00"))
			.andExpect(jsonPath("$.bySource[?(@.source=='Fee receipts')].amountIn").value("2500.00"))
			.andExpect(jsonPath("$.closing").value("92500.00"));
		// QA-ACC-004: a mismatch needs a note
		clerk.post("/api/v1/accounts/days/" + cash + "/close", """
				{"date":"%s","counted":"92000"}""".formatted(today)).andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
		clerk.post("/api/v1/accounts/days/" + cash + "/close", """
				{"date":"%s","counted":"92500"}""".formatted(today)).andExpect(status().isOk())
			.andExpect(jsonPath("$.close.difference").value("0.00"))
			.andExpect(jsonPath("$.close.closedBy").value("User clerk"));
		// Nothing more can be posted to cash on a closed day: neither an expense nor a fee payment
		entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"100"}"""
			.formatted(category("Utilities"), cash)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DAY_CLOSED"));
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"100","method":"CASH","accountId":%d}""".formatted(familyId, cash), "fee-2")
			.andExpect(jsonPath("$.code").value("DAY_CLOSED"));
		// The bank account is still open today
		admin.postWithKey("/api/v1/fees/payments", """
				{"familyId":%d,"amount":"100","method":"BANK","accountId":%d}""".formatted(familyId, bank), "fee-3")
			.andExpect(status().isCreated());
		clerk.post("/api/v1/accounts/days/" + cash + "/close", """
				{"date":"%s","counted":"92500"}""".formatted(today)).andExpect(status().isConflict());
		assertTrialBalanceNetsToZero();
	}

	@Test
	void suppliersAndBankReconciliation() throws Exception {
		opening(bank, "50000");
		var v = admin.post("/api/v1/accounts/vendors", "{\"name\":\"City Stationers\",\"phone\":\"0300 1112223\"}")
			.andExpect(status().isCreated());
		long vendor = ((Number) ApiClient.json(v, "$.id")).longValue();
		entry(clerk, """
				{"kind":"PURCHASE","categoryId":%d,"vendorId":%d,"amount":"8000","reference":"Invoice 55"}"""
			.formatted(category("Stationery and supplies"), vendor)).andExpect(jsonPath("$.status").value("APPROVED"));
		entry(clerk, """
				{"kind":"VENDOR_PAYMENT","vendorId":%d,"accountId":%d,"amount":"5000","reference":"Cheque 1001"}"""
			.formatted(vendor, bank)).andExpect(jsonPath("$.status").value("APPROVED"));
		admin.get("/api/v1/accounts/vendors").andExpect(jsonPath("$[0].balance").value("3000.00"));
		// QA-ACC-005: the cheque stays uncleared until marked
		var uncleared = admin.get("/api/v1/accounts/reconciliation/" + bank);
		List<Integer> lines = ApiClient.json(uncleared, "$[?(@.credit=='5000.00')].lineId");
		assertThat(lines).hasSize(1);
		clerk.post("/api/v1/accounts/reconciliation/" + bank, """
				{"lineIds":[%d],"clearedOn":"%s","cleared":true}""".formatted(lines.getFirst(), today))
			.andExpect(jsonPath("$.changed").value(1));
		List<Integer> after = ApiClient.json(admin.get("/api/v1/accounts/reconciliation/" + bank),
				"$[?(@.credit=='5000.00')].lineId");
		assertThat(after).isEmpty();
		admin.post("/api/v1/accounts/reconciliation/" + cash, """
				{"lineIds":[1],"cleared":true}""").andExpect(jsonPath("$.code").value("NOT_BANK"));
	}

	@Test
	void reportsMatchTheJournal() throws Exception {
		opening(cash, "100000");
		entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"12000"}"""
			.formatted(category("Utilities"), cash));
		entry(clerk, """
				{"kind":"EXPENSE","categoryId":%d,"accountId":%d,"amount":"3000"}"""
			.formatted(category("Utilities"), cash));
		entry(clerk, """
				{"kind":"INCOME","categoryId":%d,"accountId":%d,"amount":"20000"}"""
			.formatted(category("Donations"), cash));
		String period = "from=%s&to=%s".formatted(today.minusDays(10), today);
		// QA-ACC-008
		admin.get("/api/v1/accounts/reports/by-category?" + period)
			.andExpect(jsonPath("$[?(@.name=='Utilities')].amount").value("15000.00"))
			.andExpect(jsonPath("$[?(@.name=='Donations')].amount").value("20000.00"));
		admin.get("/api/v1/accounts/reports/monthly?" + period).andExpect(jsonPath("$[0].net").value("5000.00"));
		// QA-ACC-007: Σ debit = Σ credit
		var tb = admin.get("/api/v1/accounts/reports/trial-balance?asOf=" + today);
		List<String> debits = ApiClient.json(tb, "$[*].debit");
		List<String> credits = ApiClient.json(tb, "$[*].credit");
		BigDecimal d = debits.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal c = credits.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(d).isEqualByComparingTo(c).isEqualByComparingTo("135000");
		// Balance sheet: assets = liabilities + equity + surplus
		admin.get("/api/v1/accounts/reports/balance-sheet?asOf=" + today)
			.andExpect(jsonPath("$.totalAssets").value("105000.00"))
			.andExpect(jsonPath("$.totalEquity").value("100000.00"))
			.andExpect(jsonPath("$.surplus").value("5000.00"));
		admin.get("/api/v1/accounts/activity?" + period).andExpect(jsonPath("$.totalItems").isNumber());
		admin.get("/api/v1/accounts/ledger/" + cash + "?" + period + "&view=DETAILED")
			.andExpect(jsonPath("$.opening").value("0.00")).andExpect(jsonPath("$.closing").value("105000.00"))
			.andExpect(jsonPath("$.lines.length()").value(4));
		admin.get("/api/v1/accounts/ledger/" + cash + "?" + period + "&view=MONTHLY")
			.andExpect(jsonPath("$.lines[-1].balance").value("105000.00"));
	}

	@Test
	void anotherSchoolSeesNoAccounts() throws Exception {
		opening(cash, "1000");
		var e = entry(clerk, """
				{"kind":"INCOME","categoryId":%d,"accountId":%d,"amount":"500"}"""
			.formatted(category("Donations"), cash));
		long entryId = ((Number) ApiClient.json(e, "$.id")).longValue();
		SeededSchool other = TestData.school("ACB");
		TestData.admin(other, "admin", "admin-password-2");
		TestData.yearAroundToday(other, true);
		ApiClient intruder = new ApiClient(mvc);
		intruder.login(other.code(), "admin", "admin-password-2").andExpect(status().isOk());
		intruder.stepUp(outbox);
		intruder.get("/api/v1/accounts/entries/" + entryId).andExpect(status().isNotFound());
		intruder.get("/api/v1/accounts/ledger/" + cash + "?from=" + today + "&to=" + today).andExpect(status().isNotFound());
		intruder.get("/api/v1/accounts/entries").andExpect(jsonPath("$.totalItems").value(0));
		intruder.postWithKey("/api/v1/accounts/entries", """
				{"kind":"INCOME","categoryId":%d,"accountId":%d,"amount":"500"}"""
			.formatted(category("Donations"), cash), "x").andExpect(status().isNotFound());
	}
}
