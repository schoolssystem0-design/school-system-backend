package com.selectcode.sms.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Salary sheets: deductions, increments, approve → pay → lock, reversal (QA-PAY). */
@AutoConfigureMockMvc
class PayrollIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Autowired
	JdbcTemplate app;

	SeededSchool school;

	ApiClient admin;

	long ayesha;

	long bilal;

	YearMonth month = YearMonth.now(ZoneId.of("Asia/Karachi"));

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("PAY");
		TestData.admin(school, "admin", "admin-password-1");
		TestData.yearAroundToday(school, true);
		ayesha = TestData.teacher(school, "Ayesha Teacher");
		bilal = TestData.teacher(school, "Bilal No Salary");
		salary(ayesha, month.minusMonths(1).atDay(1), "50000");
		// Increment from the 1st of this month: last month keeps the old basic (QA-PAY-002)
		salary(ayesha, month.atDay(1), "60000");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.stepUp(outbox);
	}

	void salary(long staffId, LocalDate from, String basic) {
		TestDatabase.superuser().update("""
				insert into salary_profile (school_id, staff_id, effective_from, basic_salary, reason)
				values (?, ?, ?, ?, 'Test')""", school.id(), staffId, Date.valueOf(from), new BigDecimal(basic));
	}

	long sheet(YearMonth m) throws Exception {
		var r = admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(m)).andExpect(status().isCreated());
		return ((Number) ApiClient.json(r, "$.id")).longValue();
	}

	@Test
	void incrementsApplyFromTheirMonthAndStaffWithoutSalaryAreListed() throws Exception {
		var last = admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(month.minusMonths(1)))
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].basic").value("50000.00"))
			.andExpect(jsonPath("$.skipped[0]").value(org.hamcrest.Matchers.startsWith("Bilal No Salary")));
		assertThat((String) ApiClient.json(last, "$.status")).isEqualTo("DRAFT");
		admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(month))
			.andExpect(jsonPath("$.lines[0].basic").value("60000.00"));
		admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(month))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHEET_EXISTS"));
		admin.post("/api/v1/payroll/sheets", "{\"month\":\"%s\"}".formatted(month.plusMonths(1)))
			.andExpect(jsonPath("$.code").value("MONTH_IN_FUTURE"));
	}

	@Test
	void lateDaysCountAsAbsencesPerTheRule() throws Exception {
		// QA-PAY-001: 3 late days = 1 absence; 4 late + 1 absent → 2 days
		long id = sheet(month);
		var s = admin.get("/api/v1/payroll/sheets/" + id);
		long lineId = ((Number) ApiClient.json(s, "$.lines[0].id")).longValue();
		int days = ApiClient.json(s, "$.workingDays");
		var updated = admin.put("/api/v1/payroll/lines/" + lineId, """
				{"absentDays":1,"halfDays":0,"lateDays":4,"leaveDays":0,"lectures":0,"allowance":"2000","tax":"1500",
				 "otherDeduction":"500","otherNote":"Canteen"}""").andExpect(status().isOk());
		BigDecimal deduction = new BigDecimal("60000").multiply(BigDecimal.valueOf(2))
			.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
		BigDecimal earned = new BigDecimal("62000").subtract(deduction);
		updated.andExpect(jsonPath("$.lines[0].deductionDays").value("2"))
			.andExpect(jsonPath("$.lines[0].attendanceDeduction").value(deduction.toPlainString()))
			.andExpect(jsonPath("$.lines[0].earned").value(earned.setScale(2).toPlainString()))
			.andExpect(jsonPath("$.lines[0].net").value(earned.subtract(new BigDecimal("2000")).setScale(2).toPlainString()))
			.andExpect(jsonPath("$.lines[0].breakdown.lateDaysAsAbsence").value("1"));
		admin.put("/api/v1/payroll/lines/" + lineId, """
				{"absentDays":0,"halfDays":0,"lateDays":0,"leaveDays":0,"lectures":0,"tax":"99999"}""")
			.andExpect(jsonPath("$.code").value("NET_BELOW_ZERO"));
	}

	@Test
	void approvedSheetsAreLockedPaidOnceAndCorrectedByReversal() throws Exception {
		// QA-PAY-003
		long id = sheet(month);
		long lineId = ((Number) ApiClient.json(admin.get("/api/v1/payroll/sheets/" + id), "$.lines[0].id")).longValue();
		admin.put("/api/v1/payroll/lines/" + lineId, """
				{"absentDays":0,"halfDays":0,"lateDays":0,"leaveDays":0,"lectures":0,"tax":"2000"}""");
		admin.post("/api/v1/payroll/sheets/" + id + "/pay", "{\"accountId\":%d}".formatted(cash()))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		admin.postWithKey("/api/v1/payroll/sheets/" + id + "/pay", "{\"accountId\":%d}".formatted(cash()), "p1")
			.andExpect(jsonPath("$.code").value("NOT_APPROVED"));
		admin.post("/api/v1/payroll/sheets/" + id + "/approve").andExpect(jsonPath("$.status").value("APPROVED"));
		admin.post("/api/v1/payroll/sheets/" + id + "/approve").andExpect(status().isConflict());
		admin.put("/api/v1/payroll/lines/" + lineId, """
				{"absentDays":3,"halfDays":0,"lateDays":0,"leaveDays":0,"lectures":0}""")
			.andExpect(jsonPath("$.code").value("SHEET_LOCKED"));
		// The database refuses changes to a locked sheet's lines as well
		TenantContext.runAsSchool(school.id(), () -> assertThatThrownBy(
				() -> app.update("update salary_sheet_line set net = 1 where id = ?", lineId)).rootCause()
			.hasMessageContaining("locked"));
		JdbcTemplate db = TestDatabase.superuser();
		assertThat(db.queryForObject("""
				select count(*) from journal_entry where school_id = ? and source_type = 'SALARY_SHEET'""", Long.class,
				school.id())).isEqualTo(1);
		admin.postWithKey("/api/v1/payroll/sheets/" + id + "/pay", "{\"accountId\":%d}".formatted(cash()), "p1")
			.andExpect(jsonPath("$.status").value("PAID"));
		admin.postWithKey("/api/v1/payroll/sheets/" + id + "/pay", "{\"accountId\":%d}".formatted(cash()), "p1")
			.andExpect(jsonPath("$.status").value("PAID"));
		admin.postWithKey("/api/v1/payroll/sheets/" + id + "/pay", "{\"accountId\":%d}".formatted(cash()), "p2")
			.andExpect(jsonPath("$.code").value("ALREADY_PAID"));
		assertThat(db.queryForObject("""
				select count(*) from journal_entry where school_id = ? and source_type = 'SALARY_PAYMENT'""", Long.class,
				school.id())).isEqualTo(1);
		// Cash went down by the net pay (60,000 − 2,000 tax); tax is now owed
		assertThat(sum("CASH")).isEqualByComparingTo("-58000");
		assertThat(sum("TAX_PAYABLE")).isEqualByComparingTo("-2000");
		assertThat(sum("SALARY_PAYABLE")).isEqualByComparingTo("0");
		byte[] pdf = admin.get("/api/v1/payroll/sheets/" + id + "/slips").andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
		// Correction: reverse the whole sheet, then make it again
		admin.post("/api/v1/payroll/sheets/" + id + "/reverse", "{\"reason\":\"Wrong tax\"}")
			.andExpect(jsonPath("$.status").value("REVERSED"));
		assertThat(sum("CASH")).isEqualByComparingTo("0");
		assertThat(sum("SALARY_EXPENSE")).isEqualByComparingTo("0");
		sheet(month);
		BigDecimal net = db.queryForObject("select coalesce(sum(debit - credit), 0) from journal_line where school_id = ?",
				BigDecimal.class, school.id());
		assertThat(net).as("the trial balance always nets to zero").isEqualByComparingTo("0");
	}

	@Test
	void payrollNeedsItsPermissionsAndStepUp() throws Exception {
		long id = sheet(month);
		TestData.userWithPermissions(school, "clerk", "clerk-password-1",
				com.selectcode.sms.shared.security.Permissions.PAYROLL_VIEW,
				com.selectcode.sms.shared.security.Permissions.PAYROLL_MANAGE);
		ApiClient clerk = new ApiClient(mvc);
		clerk.login(school.code(), "clerk", "clerk-password-1").andExpect(status().isOk());
		clerk.get("/api/v1/payroll/sheets/" + id).andExpect(status().isOk());
		clerk.post("/api/v1/payroll/sheets/" + id + "/approve").andExpect(status().isForbidden());
		long lineId = ((Number) ApiClient.json(clerk.get("/api/v1/payroll/sheets/" + id), "$.lines[0].id")).longValue();
		clerk.put("/api/v1/payroll/lines/" + lineId, """
				{"absentDays":1,"halfDays":0,"lateDays":0,"leaveDays":0,"lectures":0}""")
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
	}

	long cash() {
		return TestDatabase.superuser().queryForObject(
				"select id from ledger_account where school_id = ? and subtype = 'CASH'", Long.class, school.id());
	}

	/** Σ debit − credit of an account kind. */
	BigDecimal sum(String subtype) {
		return TestDatabase.superuser().queryForObject("""
				select coalesce(sum(l.debit - l.credit), 0) from journal_line l
				join ledger_account a on a.id = l.ledger_account_id where l.school_id = ? and a.subtype = ?""",
				BigDecimal.class, school.id(), subtype);
	}
}
