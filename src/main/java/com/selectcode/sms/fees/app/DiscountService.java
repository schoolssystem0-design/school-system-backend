package com.selectcode.sms.fees.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discounts, scholarships and concessions (FR 10.3). A discount larger than the school's no-approval limit waits as
 * PENDING until someone with the approval permission approves it; only APPROVED discounts reach bills (QA-FEE-003).
 * Discounts change future bills only; already charged lines are corrected with adjustments.
 */
@Service
public class DiscountService {

	public record DiscountView(long id, long studentId, String studentName, String grNo, String className,
			Long feeHeadId, String headName, String kind, String value, String reason, LocalDate validFrom,
			LocalDate validTo, String status, String requestedBy, String decidedBy, Instant decidedAt,
			String decisionNote, Instant createdAt) {
	}

	public record DiscountInput(long studentId, Long feeHeadId, String kind, String value, String reason,
			LocalDate validFrom, LocalDate validTo) {
	}

	private final JdbcTemplate jdbc;

	private final SettingsService settings;

	private final AuditService audit;

	private final Clock clock;

	DiscountService(JdbcTemplate jdbc, SettingsService settings, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<DiscountView> list(String status, Long studentId) {
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("""
				select d.id, d.student_id, s.name, s.gr_no,
				       (select c.name from enrolment e join school_class c on c.id = e.class_id
				        where e.student_id = s.id order by e.effective_from desc limit 1),
				       d.fee_head_id, h.name, d.kind, d.value, d.reason, d.valid_from, d.valid_to, d.status,
				       d.requested_by, d.decided_by, d.decided_at, d.decision_note, d.created_at
				from discount d join student s on s.id = d.student_id left join fee_head h on h.id = d.fee_head_id
				where 1 = 1""");
		if (status != null && !status.isBlank()) {
			sql.append(" and d.status = ?");
			args.add(status);
		}
		if (studentId != null) {
			sql.append(" and d.student_id = ?");
			args.add(studentId);
		}
		sql.append(" order by case d.status when 'PENDING' then 0 else 1 end, d.created_at desc limit 500");
		return jdbc.query(sql.toString(), (rs, i) -> new DiscountView(rs.getLong(1), rs.getLong(2), rs.getString(3),
				rs.getString(4), rs.getString(5), (Long) rs.getObject(6), rs.getString(7), rs.getString(8),
				Amounts.text(rs.getBigDecimal(9)), rs.getString(10), rs.getDate(11).toLocalDate(),
				rs.getDate(12) == null ? null : rs.getDate(12).toLocalDate(), rs.getString(13), rs.getString(14),
				rs.getString(15), rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toInstant(),
				rs.getString(17), rs.getTimestamp(18).toInstant()), args.toArray());
	}

	public DiscountView get(long id) {
		return jdbc.queryForList("select student_id from discount where id = ?", Long.class, id)
			.stream()
			.findFirst()
			.flatMap(studentId -> list(null, studentId).stream().filter(d -> d.id() == id).findFirst())
			.orElseThrow(() -> ApiException.notFound("Discount"));
	}

	@Transactional
	public DiscountView create(DiscountInput in) {
		String kind = in.kind();
		if (!"AMOUNT".equals(kind) && !"PERCENT".equals(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose a fixed amount or a percentage");
		}
		BigDecimal value = Amounts.parse(in.value(), "Discount", false);
		if ("PERCENT".equals(kind) && value.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw ApiException.businessRule("AMOUNT_INVALID", "A percentage cannot be more than 100");
		}
		String reason = PakFormats.trimToNull(in.reason());
		if (reason == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason, e.g. staff child or scholarship");
		}
		LocalDate from = in.validFrom() == null ? Amounts.today(clock) : in.validFrom();
		if (in.validTo() != null && in.validTo().isBefore(from)) {
			throw ApiException.businessRule("DATES_INVALID", "The end date is before the start date");
		}
		Long studentCount = jdbc.queryForObject("select count(*) from student where id = ?", Long.class, in.studentId());
		if (studentCount == null || studentCount == 0) {
			throw ApiException.notFound("Student");
		}
		if (in.feeHeadId() != null) {
			Long headCount = jdbc.queryForObject("select count(*) from fee_head where id = ? and system_code is null",
					Long.class, in.feeHeadId());
			if (headCount == null || headCount == 0) {
				throw ApiException.notFound("Fee head");
			}
		}
		BigDecimal percent = effectivePercent(in.studentId(), in.feeHeadId(), kind, value);
		boolean withinLimit = percent != null
				&& percent.compareTo(BigDecimal.valueOf(settings.integer(SettingKey.MAX_DISCOUNT_WITHOUT_APPROVAL))) <= 0;
		boolean canApprove = CurrentActor.hasPermission(Permissions.FEES_DISCOUNT_APPROVE);
		String status = withinLimit || canApprove ? "APPROVED" : "PENDING";
		String actor = CurrentActor.require().displayName();
		Long id = jdbc.queryForObject("""
				insert into discount (school_id, student_id, fee_head_id, kind, value, reason, valid_from, valid_to,
				                      status, requested_by, decided_by, decided_at, decision_note)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireSchoolId(), in.studentId(), in.feeHeadId(), kind, value, reason,
				Date.valueOf(from), in.validTo() == null ? null : Date.valueOf(in.validTo()), status, actor,
				"APPROVED".equals(status) ? actor : null,
				"APPROVED".equals(status) ? java.sql.Timestamp.from(clock.instant()) : null,
				"APPROVED".equals(status) ? (withinLimit ? "Within the no-approval limit" : "Approved when given") : null);
		audit.record("DISCOUNT_CREATED", "student", in.studentId(), null,
				Map.of("discountId", id, "kind", kind, "value", value.toPlainString(), "status", status), reason);
		return get(id);
	}

	@Transactional
	public DiscountView decide(long id, boolean approve, String note) {
		DiscountView d = get(id);
		if (!"PENDING".equals(d.status())) {
			throw ApiException.conflict("ALREADY_DECIDED", "This discount is already " + d.status().toLowerCase());
		}
		String clean = PakFormats.trimToNull(note);
		if (!approve && clean == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for rejecting");
		}
		jdbc.update("""
				update discount set status = ?, decided_by = ?, decided_at = ?, decision_note = ? where id = ?""",
				approve ? "APPROVED" : "REJECTED", CurrentActor.require().displayName(),
				java.sql.Timestamp.from(clock.instant()), clean, id);
		audit.record(approve ? "DISCOUNT_APPROVED" : "DISCOUNT_REJECTED", "student", d.studentId(),
				Map.of("discountId", id, "status", d.status()), Map.of("status", approve ? "APPROVED" : "REJECTED"),
				clean);
		return get(id);
	}

	/** Stops a discount from a date; bills already made keep it. */
	@Transactional
	public DiscountView end(long id, LocalDate lastDay, String reason) {
		DiscountView d = get(id);
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for ending the discount");
		}
		if (!"APPROVED".equals(d.status()) && !"PENDING".equals(d.status())) {
			throw ApiException.conflict("ALREADY_ENDED", "This discount is no longer active");
		}
		LocalDate end = lastDay == null ? Amounts.today(clock) : lastDay;
		if (end.isBefore(d.validFrom())) {
			end = d.validFrom();
		}
		jdbc.update("update discount set status = 'ENDED', valid_to = ? where id = ?", Date.valueOf(end), id);
		audit.record("DISCOUNT_ENDED", "student", d.studentId(), Map.of("discountId", id, "status", d.status()),
				Map.of("status", "ENDED", "validTo", end.toString()), why);
		return get(id);
	}

	/** The discount as a percentage of the head's amount for the student (null when it cannot be worked out). */
	private BigDecimal effectivePercent(long studentId, Long headId, String kind, BigDecimal value) {
		if ("PERCENT".equals(kind)) {
			return value;
		}
		if (headId == null) {
			return null;
		}
		List<BigDecimal> amounts = jdbc.queryForList("""
				select coalesce(
				  (select o.amount from student_fee_override o where o.student_id = e.student_id
				    and o.academic_year_id = e.academic_year_id and o.fee_head_id = ?),
				  (select f.amount from fee_structure f where f.academic_year_id = e.academic_year_id
				    and f.class_id = e.class_id and f.fee_head_id = ?))
				from enrolment e where e.student_id = ? order by e.effective_from desc limit 1""", BigDecimal.class,
				headId, headId, studentId);
		BigDecimal base = amounts.isEmpty() ? null : amounts.getFirst();
		if (base == null || base.signum() == 0) {
			return null;
		}
		return value.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
	}
}
