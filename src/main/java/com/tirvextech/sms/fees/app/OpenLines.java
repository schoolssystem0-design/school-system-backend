package com.selectcode.sms.fees.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Charged lines with what is still owed, oldest due first (the default allocation order, FR 10.7). */
@Component
public class OpenLines {

	public record OpenLine(long billLineId, long billId, String billNo, long studentId, String studentName,
			String grNo, long feeHeadId, String headName, String periodKey, String description, LocalDate issueDate,
			LocalDate dueDate, String net, String adjusted, String paid, String balance) {

		BigDecimal balanceAmount() {
			return new BigDecimal(balance);
		}
	}

	public record Credit(long paymentId, Long studentId, String receiptNo, BigDecimal remaining) {
	}

	private final JdbcTemplate jdbc;

	OpenLines(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Lines with a balance above zero for the family (or one student), oldest due date first. */
	public List<OpenLine> forFamily(long familyId, Long studentId) {
		List<Object> args = new ArrayList<>(List.of(familyId));
		String studentFilter = "";
		if (studentId != null) {
			studentFilter = " and v.student_id = ?";
			args.add(studentId);
		}
		return jdbc.query("""
				select v.bill_line_id, v.bill_id, v.bill_no, v.student_id, s.name, s.gr_no, v.fee_head_id, h.name,
				       v.period_key, v.description, v.issue_date, v.due_date, v.net, v.adjusted, v.paid, v.balance
				from fee_line_status v join student s on s.id = v.student_id join fee_head h on h.id = v.fee_head_id
				where v.family_id = ? and v.balance > 0""" + studentFilter + " " + """
				 order by v.due_date, v.bill_id, h.display_order, v.bill_line_id""",
				(rs, i) -> new OpenLine(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
						rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10),
						rs.getDate(11).toLocalDate(), rs.getDate(12).toLocalDate(), Amounts.text(rs.getBigDecimal(13)),
						Amounts.text(rs.getBigDecimal(14)), Amounts.text(rs.getBigDecimal(15)),
						Amounts.text(rs.getBigDecimal(16))),
				args.toArray());
	}

	/** Unused payment amounts (advance credit) of a family, oldest first. */
	public List<Credit> credits(long familyId) {
		return jdbc.query("""
				select payment_id, student_id, receipt_no, remaining from payment_credit
				where family_id = ? and remaining > 0 order by payment_date, payment_id""",
				(rs, i) -> new Credit(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getBigDecimal(4)),
				familyId);
	}

	public BigDecimal creditTotal(long familyId) {
		return credits(familyId).stream().map(Credit::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** What the family owes in total (all years), before credit. */
	public BigDecimal owed(long familyId) {
		return Amounts.nz(jdbc.queryForObject(
				"select sum(balance) from fee_line_status where family_id = ? and balance > 0", BigDecimal.class,
				familyId));
	}
}
