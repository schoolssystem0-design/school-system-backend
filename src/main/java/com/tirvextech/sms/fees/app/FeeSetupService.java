package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fee heads (FR 10.1), class amounts and student amounts per year (FR 10.2), sibling rules (FR 10.4). */
@Service
public class FeeSetupService {

	public static final Set<String> FREQUENCIES = Set.of("MONTHLY", "YEARLY", "ONE_TIME", "PER_TERM", "CUSTOM");

	public record HeadView(long id, String name, String frequency, String maxAmount, int displayOrder,
			boolean refundable, String systemCode, String status, long version) {
	}

	public record HeadInput(String name, String frequency, String maxAmount, Integer displayOrder, boolean refundable) {
	}

	public record ClassAmount(long classId, long feeHeadId, String amount) {
	}

	public record MissingAmount(long classId, String className, long feeHeadId, String headName) {
	}

	public record OverrideView(long id, long studentId, long feeHeadId, String headName, String amount, String reason) {
	}

	public record SiblingRule(long id, int childOrder, String percent, List<Long> feeHeadIds) {
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	FeeSetupService(JdbcTemplate jdbc, AuditService audit, YearContext yearContext, AcademicYearLookup years) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.yearContext = yearContext;
		this.years = years;
	}

	// ------------------------------------------------------------------ heads

	@Transactional(readOnly = true)
	public List<HeadView> heads() {
		return jdbc.query("""
				select id, name, frequency, max_amount, display_order, refundable, system_code, status, version
				from fee_head order by display_order, name""",
				(rs, i) -> new HeadView(rs.getLong(1), rs.getString(2), rs.getString(3),
						Amounts.text(rs.getBigDecimal(4)), rs.getInt(5), rs.getBoolean(6), rs.getString(7),
						rs.getString(8), rs.getLong(9)));
	}

	public HeadView head(long id) {
		return heads().stream().filter(h -> h.id() == id).findFirst().orElseThrow(() -> ApiException.notFound("Fee head"));
	}

	@Transactional
	public HeadView createHead(HeadInput in) {
		String name = cleanName(in.name());
		String frequency = frequency(in.frequency());
		BigDecimal max = in.maxAmount() == null || in.maxAmount().isBlank() ? null
				: Amounts.parse(in.maxAmount(), "Maximum amount", false);
		int order = in.displayOrder() != null ? in.displayOrder()
				: heads().stream().filter(h -> h.systemCode() == null).mapToInt(HeadView::displayOrder).max().orElse(0)
						+ 1;
		try {
			Long id = jdbc.queryForObject("""
					insert into fee_head (school_id, name, frequency, max_amount, display_order, refundable)
					values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), name,
					frequency, max, order, in.refundable());
			audit.record("FEE_HEAD_CREATED", "fee_head", id, null, Map.of("name", name, "frequency", frequency));
			return head(id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "A fee head called " + name + " already exists");
		}
	}

	@Transactional
	public HeadView updateHead(long id, HeadInput in, long version) {
		HeadView before = head(id);
		if (before.version() != version) {
			throw ApiException.conflict("CONFLICT_VERSION", "The fee head was changed by someone else. Reload and try again.");
		}
		String name = cleanName(in.name());
		String frequency = before.systemCode() != null ? before.frequency() : frequency(in.frequency());
		if (!frequency.equals(before.frequency()) && used(id)) {
			throw ApiException.conflict("HEAD_IN_USE", "This head has been billed; its frequency cannot change");
		}
		BigDecimal max = in.maxAmount() == null || in.maxAmount().isBlank() ? null
				: Amounts.parse(in.maxAmount(), "Maximum amount", false);
		try {
			jdbc.update("""
					update fee_head set name = ?, frequency = ?, max_amount = ?, display_order = ?, refundable = ?,
					       updated_at = now(), version = version + 1
					where id = ?""", name, frequency, max,
					in.displayOrder() == null ? before.displayOrder() : in.displayOrder(), in.refundable(), id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("NAME_TAKEN", "A fee head called " + name + " already exists");
		}
		HeadView after = head(id);
		audit.record("FEE_HEAD_UPDATED", "fee_head", id, before, after);
		return after;
	}

	@Transactional
	public HeadView setHeadStatus(long id, boolean active) {
		HeadView before = head(id);
		jdbc.update("update fee_head set status = ?, updated_at = now(), version = version + 1 where id = ?",
				active ? "ACTIVE" : "INACTIVE", id);
		audit.record(active ? "FEE_HEAD_ACTIVATED" : "FEE_HEAD_DEACTIVATED", "fee_head", id,
				Map.of("status", before.status()), Map.of("status", active ? "ACTIVE" : "INACTIVE"));
		return head(id);
	}

	private boolean used(long headId) {
		Long n = jdbc.queryForObject("select count(*) from bill_line where fee_head_id = ?", Long.class, headId);
		return n != null && n > 0;
	}

	// --------------------------------------------------------- class amounts

	@Transactional(readOnly = true)
	public List<ClassAmount> classAmounts(long yearId) {
		return jdbc.query("select class_id, fee_head_id, amount from fee_structure where academic_year_id = ?",
				(rs, i) -> new ClassAmount(rs.getLong(1), rs.getLong(2), Amounts.text(rs.getBigDecimal(3))), yearId);
	}

	/** Saves the given cells; a blank amount removes the cell (the class is then flagged as missing that head). */
	@Transactional
	public List<ClassAmount> saveClassAmounts(long yearId, List<ClassAmount> cells) {
		YearRef year = year(yearId);
		yearContext.requireWritable(year);
		Map<Long, HeadView> heads = new HashMap<>();
		heads().forEach(h -> heads.put(h.id(), h));
		long schoolId = TenantContext.requireSchoolId();
		List<Map<String, Object>> changes = new ArrayList<>();
		for (ClassAmount cell : cells) {
			HeadView head = heads.get(cell.feeHeadId());
			if (head == null) {
				throw ApiException.notFound("Fee head");
			}
			if (cell.amount() == null || cell.amount().isBlank()) {
				jdbc.update("delete from fee_structure where academic_year_id = ? and fee_head_id = ? and class_id = ?",
						yearId, cell.feeHeadId(), cell.classId());
				changes.add(Map.of("classId", cell.classId(), "headId", cell.feeHeadId(), "amount", "removed"));
				continue;
			}
			BigDecimal amount = Amounts.parse(cell.amount(), head.name(), true);
			checkMax(head, amount);
			jdbc.update("""
					insert into fee_structure (school_id, academic_year_id, fee_head_id, class_id, amount)
					values (?, ?, ?, ?, ?)
					on conflict (school_id, academic_year_id, fee_head_id, class_id)
					do update set amount = excluded.amount, updated_at = now()""", schoolId, yearId, cell.feeHeadId(),
					cell.classId(), amount);
			changes.add(Map.of("classId", cell.classId(), "headId", cell.feeHeadId(), "amount", amount.toPlainString()));
		}
		audit.record("FEE_STRUCTURE_SAVED", "academic_year", yearId, null, Map.of("cells", changes));
		return classAmounts(yearId);
	}

	/** Active classes without an amount for an active head in the year (FR 10.2: flagged, never silently zero). */
	@Transactional(readOnly = true)
	public List<MissingAmount> missingAmounts(long yearId) {
		return jdbc.query("""
				select c.id, c.name, h.id, h.name from school_class c
				cross join fee_head h
				where c.status = 'ACTIVE' and h.status = 'ACTIVE' and h.system_code is null
				  and not exists (select 1 from fee_structure f where f.academic_year_id = ? and f.class_id = c.id
				                  and f.fee_head_id = h.id)
				order by c.display_order, h.display_order""",
				(rs, i) -> new MissingAmount(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), yearId);
	}

	// ------------------------------------------------------- student amounts

	@Transactional(readOnly = true)
	public List<OverrideView> overrides(long studentId, long yearId) {
		return jdbc.query("""
				select o.id, o.student_id, o.fee_head_id, h.name, o.amount, o.reason from student_fee_override o
				join fee_head h on h.id = o.fee_head_id
				where o.student_id = ? and o.academic_year_id = ? order by h.display_order""",
				(rs, i) -> new OverrideView(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
						Amounts.text(rs.getBigDecimal(5)), rs.getString(6)), studentId, yearId);
	}

	/** A student-specific amount for a head in a year (replaces the class amount on future bills). */
	@Transactional
	public List<OverrideView> saveOverride(long studentId, long yearId, long feeHeadId, String rawAmount, String reason) {
		YearRef year = year(yearId);
		yearContext.requireWritable(year);
		HeadView head = head(feeHeadId);
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give a reason for the student's own amount");
		}
		Long exists = jdbc.queryForObject("select count(*) from student where id = ?", Long.class, studentId);
		if (exists == null || exists == 0) {
			throw ApiException.notFound("Student");
		}
		if (rawAmount == null || rawAmount.isBlank()) {
			jdbc.update("delete from student_fee_override where student_id = ? and academic_year_id = ? and fee_head_id = ?",
					studentId, yearId, feeHeadId);
			audit.record("FEE_OVERRIDE_REMOVED", "student", studentId, Map.of("head", head.name()), null, why);
			return overrides(studentId, yearId);
		}
		BigDecimal amount = Amounts.parse(rawAmount, head.name(), true);
		checkMax(head, amount);
		jdbc.update("""
				insert into student_fee_override (school_id, student_id, academic_year_id, fee_head_id, amount, reason)
				values (?, ?, ?, ?, ?, ?)
				on conflict (school_id, student_id, academic_year_id, fee_head_id)
				do update set amount = excluded.amount, reason = excluded.reason, updated_at = now()""",
				TenantContext.requireSchoolId(), studentId, yearId, feeHeadId, amount, why);
		audit.record("FEE_OVERRIDE_SAVED", "student", studentId, null,
				Map.of("head", head.name(), "amount", amount.toPlainString(), "year", year.name()), why);
		return overrides(studentId, yearId);
	}

	// ---------------------------------------------------------- sibling rules

	@Transactional(readOnly = true)
	public List<SiblingRule> siblingRules() {
		return jdbc.query("select id, child_order, percent, fee_head_ids from sibling_discount_rule order by child_order",
				(rs, i) -> new SiblingRule(rs.getLong(1), rs.getInt(2), Amounts.text(rs.getBigDecimal(3)),
						longs(rs.getArray(4))));
	}

	/** Replaces all sibling rules, e.g. 2nd child 10%, 3rd and later 20% (FR 10.4). */
	@Transactional
	public List<SiblingRule> saveSiblingRules(List<SiblingRule> rules) {
		List<SiblingRule> before = siblingRules();
		Set<Integer> orders = new java.util.HashSet<>();
		for (SiblingRule r : rules) {
			if (r.childOrder() < 2 || r.childOrder() > 10) {
				throw ApiException.businessRule("ORDER_INVALID", "Sibling discounts start from the 2nd child");
			}
			if (!orders.add(r.childOrder())) {
				throw ApiException.businessRule("ORDER_DUPLICATE", "Each child position can have one rule");
			}
			BigDecimal percent = Amounts.parse(r.percent(), "Percentage", false);
			if (percent.compareTo(BigDecimal.valueOf(100)) > 0) {
				throw ApiException.businessRule("AMOUNT_INVALID", "Percentage cannot be more than 100");
			}
		}
		jdbc.update("delete from sibling_discount_rule");
		long schoolId = TenantContext.requireSchoolId();
		for (SiblingRule r : rules) {
			for (Long headId : r.feeHeadIds() == null ? List.<Long>of() : r.feeHeadIds()) {
				head(headId);
			}
			jdbc.update("""
					insert into sibling_discount_rule (school_id, child_order, percent, fee_head_ids)
					values (?, ?, ?, ?)""", schoolId, r.childOrder(), Amounts.parse(r.percent(), "Percentage", false),
					jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint",
							(r.feeHeadIds() == null ? List.<Long>of() : r.feeHeadIds()).toArray())));
		}
		List<SiblingRule> after = siblingRules();
		audit.record("SIBLING_RULES_SAVED", "sibling_discount_rule", null, before, after);
		return after;
	}

	// ---------------------------------------------------------------- helpers

	private YearRef year(long id) {
		return years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
	}

	private static void checkMax(HeadView head, BigDecimal amount) {
		if (head.maxAmount() != null && amount.compareTo(new BigDecimal(head.maxAmount())) > 0) {
			throw ApiException.businessRule("ABOVE_MAXIMUM",
					head.name() + " cannot be more than " + Amounts.rupees(new BigDecimal(head.maxAmount())));
		}
	}

	private static String cleanName(String raw) {
		String name = PakFormats.trimToNull(raw);
		if (name == null || name.length() > 80) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter a name (up to 80 characters)");
		}
		return name;
	}

	private static String frequency(String raw) {
		if (raw == null || !FREQUENCIES.contains(raw)) {
			throw ApiException.businessRule("FREQUENCY_INVALID", "Choose how often the fee is charged");
		}
		return raw;
	}

	static List<Long> longs(Array array) throws java.sql.SQLException {
		if (array == null) {
			return List.of();
		}
		Object[] values = (Object[]) array.getArray();
		List<Long> list = new ArrayList<>();
		for (Object v : values) {
			list.add(((Number) v).longValue());
		}
		return list;
	}
}
