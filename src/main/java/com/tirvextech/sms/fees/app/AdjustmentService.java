package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections to charged fees (FR 10.13): a mistaken charge is reduced or removed, a fee waived, or an unrecoverable
 * balance written off as bad debt. The original line never changes; the adjustment is a new row with a reason that
 * takes effect only when approved (QA-FEE-021). Someone who may approve has their own requests approved at once.
 */
@Service
public class AdjustmentService {

	public static final Set<String> KINDS = Set.of("CORRECTION", "WAIVER", "BAD_DEBT");

	public record AdjustmentInput(long billLineId, String amount, String kind, String reason) {
	}

	public record AdjustmentView(long id, long billLineId, String billNo, long studentId, String studentName,
			String grNo, String headName, String description, String lineNet, String amount, String kind,
			String reason, String requestedBy, Instant requestedAt, String status, String decidedBy, String note,
			Instant decidedAt) {
	}

	private final JdbcTemplate jdbc;

	private final FeeLedger ledger;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final AuditService audit;

	private final CreditApplier credit;

	private final java.time.Clock clock;

	AdjustmentService(JdbcTemplate jdbc, FeeLedger ledger, YearContext yearContext, AcademicYearLookup years,
			AuditService audit, CreditApplier credit, java.time.Clock clock) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.yearContext = yearContext;
		this.years = years;
		this.audit = audit;
		this.credit = credit;
		this.clock = clock;
	}

	@Transactional
	public AdjustmentView request(AdjustmentInput in) {
		String kind = in.kind() == null ? "CORRECTION" : in.kind();
		if (!KINDS.contains(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose correction, waiver or bad debt");
		}
		String reason = PakFormats.trimToNull(in.reason());
		if (reason == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for the correction");
		}
		BigDecimal amount = signed(in.amount());
		if (!"CORRECTION".equals(kind) && amount.signum() > 0) {
			throw ApiException.businessRule("AMOUNT_INVALID", "A waiver or write-off reduces what is owed");
		}
		Map<String, Object> line = line(in.billLineId());
		long familyId = ((Number) line.get("family_id")).longValue();
		ledger.lockFamily(familyId);
		yearContext.requireWritable(years.findById(((Number) line.get("academic_year_id")).longValue()).orElseThrow());
		checkTotal(in.billLineId(), amount);
		Long id = jdbc.queryForObject("""
				insert into fee_adjustment (school_id, bill_line_id, amount, kind, reason, requested_by)
				values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), in.billLineId(),
				amount, kind, reason, CurrentActor.require().displayName());
		audit.record("FEE_ADJUSTMENT_REQUESTED", "bill_line", in.billLineId(), null,
				Map.of("adjustmentId", id, "amount", amount.toPlainString(), "kind", kind), reason);
		if (CurrentActor.hasPermission(Permissions.FEES_ADJUST_APPROVE)) {
			decideInternal(id, true, "Approved when requested");
		}
		return get(id);
	}

	@Transactional
	public AdjustmentView decide(long id, boolean approve, String note) {
		String clean = PakFormats.trimToNull(note);
		if (!approve && clean == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for rejecting");
		}
		decideInternal(id, approve, clean);
		return get(id);
	}

	private void decideInternal(long id, boolean approve, String note) {
		AdjustmentView a = get(id);
		if (!"PENDING".equals(a.status())) {
			throw ApiException.conflict("ALREADY_DECIDED", "This correction is already " + a.status().toLowerCase());
		}
		Map<String, Object> line = line(a.billLineId());
		long familyId = ((Number) line.get("family_id")).longValue();
		ledger.lockFamily(familyId);
		if (approve) {
			checkTotal(a.billLineId(), new BigDecimal(a.amount()));
		}
		jdbc.update("""
				insert into fee_adjustment_decision (adjustment_id, school_id, decision, decided_by, note)
				values (?, ?, ?, ?, ?)""", id, TenantContext.requireSchoolId(), approve ? "APPROVED" : "REJECTED",
				CurrentActor.require().displayName(), note);
		audit.record(approve ? "FEE_ADJUSTMENT_APPROVED" : "FEE_ADJUSTMENT_REJECTED", "bill_line", a.billLineId(),
				Map.of("adjustmentId", id), Map.of("decision", approve ? "APPROVED" : "REJECTED"), note);
		if (approve) {
			releaseOverpayment(a.billLineId(), familyId, id);
		}
	}

	/**
	 * A charge reduced after it was paid leaves the line overpaid. The excess goes back to the paying payments as
	 * advance credit (Dr fee income, Cr fees received in advance) and is then used for the family's other dues.
	 */
	private void releaseOverpayment(long lineId, long familyId, long adjustmentId) {
		BigDecimal balance = jdbc.queryForObject("select balance from fee_line_status where bill_line_id = ?",
				BigDecimal.class, lineId);
		if (balance.signum() >= 0) {
			return;
		}
		BigDecimal excess = balance.negate();
		java.time.LocalDate today = Amounts.today(clock);
		long entry = ledger.post(today, "FEE_CREDIT", "ADJUSTMENT", adjustmentId,
				"Overpaid after a correction: returned to advance credit", null,
				List.of(FeeLedger.Line.debit(ledger.accountOf(FeeLedger.FEE_INCOME), excess),
						FeeLedger.Line.credit(ledger.accountOf(FeeLedger.STUDENT_ADVANCE), excess)));
		List<Object[]> paid = jdbc.query("""
				select payment_id, sum(amount) from payment_allocation where bill_line_id = ?
				group by payment_id having sum(amount) > 0 order by max(id) desc""",
				(rs, i) -> new Object[] { rs.getLong(1), rs.getBigDecimal(2) }, lineId);
		BigDecimal left = excess;
		for (Object[] p : paid) {
			if (left.signum() <= 0) {
				break;
			}
			BigDecimal take = ((BigDecimal) p[1]).min(left);
			jdbc.update("""
					insert into payment_allocation (school_id, payment_id, bill_line_id, amount, kind, allocation_date,
					                                journal_entry_id)
					values (?, ?, ?, ?, 'RELEASE', ?, ?)""", TenantContext.requireSchoolId(), p[0], lineId, take.negate(),
					java.sql.Date.valueOf(today), entry);
			left = left.subtract(take);
		}
		credit.apply(familyId, today);
	}

	@Transactional(readOnly = true)
	public List<AdjustmentView> list(String status, Long studentId) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				select a.id, a.bill_line_id, b.bill_no, bl.student_id, s.name, s.gr_no, h.name, bl.description, bl.net,
				       a.amount, a.kind, a.reason, a.requested_by, a.requested_at,
				       coalesce(d.decision, 'PENDING'), d.decided_by, d.note, d.decided_at
				from fee_adjustment a join bill_line bl on bl.id = a.bill_line_id join bill b on b.id = bl.bill_id
				join student s on s.id = bl.student_id join fee_head h on h.id = bl.fee_head_id
				left join fee_adjustment_decision d on d.adjustment_id = a.id
				where 1 = 1""");
		if (status != null && !status.isBlank()) {
			sql.append(" and coalesce(d.decision, 'PENDING') = ?");
			args.add(status);
		}
		if (studentId != null) {
			sql.append(" and bl.student_id = ?");
			args.add(studentId);
		}
		sql.append(" order by a.id desc limit 500");
		return jdbc.query(sql.toString(), (rs, i) -> new AdjustmentView(rs.getLong(1), rs.getLong(2), rs.getString(3),
				rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
				Amounts.text(rs.getBigDecimal(9)), Amounts.text(rs.getBigDecimal(10)), rs.getString(11),
				rs.getString(12), rs.getString(13), rs.getTimestamp(14).toInstant(), rs.getString(15), rs.getString(16),
				rs.getString(17), rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toInstant()), args.toArray());
	}

	public AdjustmentView get(long id) {
		List<Long> students = jdbc.queryForList("""
				select bl.student_id from fee_adjustment a join bill_line bl on bl.id = a.bill_line_id where a.id = ?""",
				Long.class, id);
		if (students.isEmpty()) {
			throw ApiException.notFound("Correction");
		}
		return list(null, students.getFirst()).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
	}

	/** A line can be reduced at most to zero (net + approved adjustments never below zero). */
	private void checkTotal(long lineId, BigDecimal amount) {
		BigDecimal charged = jdbc.queryForObject("select net + adjusted from fee_line_status where bill_line_id = ?",
				BigDecimal.class, lineId);
		if (charged.add(amount).signum() < 0) {
			throw ApiException.businessRule("BELOW_ZERO",
					"The correction is larger than the charge (" + Amounts.rupees(charged) + ")");
		}
	}

	private Map<String, Object> line(long lineId) {
		return jdbc.queryForList("""
				select b.family_id, b.academic_year_id from bill_line bl join bill b on b.id = bl.bill_id where bl.id = ?""",
				lineId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Fee line"));
	}

	private static BigDecimal signed(String raw) {
		String text = raw == null ? "" : raw.trim();
		boolean negative = text.startsWith("-");
		BigDecimal value = Amounts.parse(negative ? text.substring(1) : text, "Amount", false);
		return negative ? value.negate() : value;
	}
}
