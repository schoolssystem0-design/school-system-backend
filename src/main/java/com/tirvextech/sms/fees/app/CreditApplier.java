package com.selectcode.sms.fees.app;

import com.selectcode.sms.fees.app.OpenLines.Credit;
import com.selectcode.sms.fees.app.OpenLines.OpenLine;
import com.selectcode.sms.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles open lines from advance credit (FR 10.10): the unused part of earlier payments, oldest payment first and
 * oldest due line first. A payment made for one student only settles that student's lines; a family payment settles
 * any child's. Posts Dr fees received in advance / Cr fee income.
 */
@Component
public class CreditApplier {

	private final JdbcTemplate jdbc;

	private final OpenLines openLines;

	private final FeeLedger ledger;

	CreditApplier(JdbcTemplate jdbc, OpenLines openLines, FeeLedger ledger) {
		this.jdbc = jdbc;
		this.openLines = openLines;
		this.ledger = ledger;
	}

	/** @return the amount of credit used */
	@Transactional(propagation = Propagation.MANDATORY)
	public BigDecimal apply(long familyId, LocalDate date) {
		ledger.lockFamily(familyId);
		List<Credit> credits = openLines.credits(familyId);
		if (credits.isEmpty()) {
			return BigDecimal.ZERO;
		}
		List<OpenLine> lines = openLines.forFamily(familyId, null);
		Map<Long, BigDecimal> left = new HashMap<>();
		lines.forEach(l -> left.put(l.billLineId(), l.balanceAmount()));
		record Allocation(long paymentId, long lineId, BigDecimal amount) {
		}
		List<Allocation> allocations = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Credit credit : credits) {
			BigDecimal remaining = credit.remaining();
			for (OpenLine line : lines) {
				if (remaining.signum() <= 0) {
					break;
				}
				if (credit.studentId() != null && credit.studentId() != line.studentId()) {
					continue;
				}
				BigDecimal owed = left.get(line.billLineId());
				if (owed.signum() <= 0) {
					continue;
				}
				BigDecimal take = owed.min(remaining);
				allocations.add(new Allocation(credit.paymentId(), line.billLineId(), take));
				left.put(line.billLineId(), owed.subtract(take));
				remaining = remaining.subtract(take);
				total = total.add(take);
			}
		}
		if (total.signum() == 0) {
			return BigDecimal.ZERO;
		}
		long entry = ledger.post(date, "FEE_CREDIT", "CREDIT_APPLICATION", familyId, "Advance credit applied to fees",
				null, List.of(FeeLedger.Line.debit(ledger.accountOf(FeeLedger.STUDENT_ADVANCE), total),
						FeeLedger.Line.credit(ledger.accountOf(FeeLedger.FEE_INCOME), total)));
		long schoolId = TenantContext.requireSchoolId();
		for (Allocation a : allocations) {
			jdbc.update("""
					insert into payment_allocation (school_id, payment_id, bill_line_id, amount, kind, allocation_date,
					                                journal_entry_id)
					values (?, ?, ?, ?, 'CREDIT', ?, ?)""", schoolId, a.paymentId(), a.lineId(), a.amount(),
					Date.valueOf(date), entry);
		}
		return total;
	}
}
