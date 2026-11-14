package com.tirvextech.sms.fees.app;

import com.tirvextech.sms.fees.app.OpenLines.OpenLine;
import com.tirvextech.sms.shared.security.CurrentActor;
import com.tirvextech.sms.shared.security.Permissions;
import com.tirvextech.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the fee counter needs to keep collecting when the internet is down: every student to search for (studying, or
 * who left but still owes) with their family, and each family's open dues and advance. Downloaded in the background
 * while online; a payment taken offline is sent with its own idempotency key once the connection is back, and the
 * server then allocates it and issues the receipt number.
 */
@Component
public class OfflineSnapshot {

	public record OfflineStudent(long id, String grNo, String name, String gender, String className, String sectionName,
			Long sectionId, long familyId, String familyNo, String fatherName, String fatherPhone, String status) {
	}

	public record OfflineFamily(long familyId, String owed, String credit, String balance, List<OpenLine> lines) {
	}

	public record FeeSnapshot(Instant at, List<OfflineStudent> students, List<OfflineFamily> families) {
	}

	private final JdbcTemplate jdbc;

	private final YearContext yearContext;

	private final Clock clock;

	OfflineSnapshot(JdbcTemplate jdbc, YearContext yearContext, Clock clock) {
		this.jdbc = jdbc;
		this.yearContext = yearContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public FeeSnapshot snapshot() {
		var year = yearContext.requireSelected();
		LocalDate today = LocalDate.now(clock.withZone(Amounts.ZONE));
		LocalDate on = today.isBefore(year.startDate()) ? year.startDate() : today.isAfter(year.endDate()) ? year.endDate() : today;
		boolean phones = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);

		List<OfflineStudent> students = jdbc.query("""
				select s.id, s.gr_no, s.name, s.gender, c.name, sec.name, e.section_id, f.id, f.family_no, f.father_name,
				       f.father_phone, s.status
				from student s join family f on f.id = s.family_id
				left join enrolment e on e.student_id = s.id and e.academic_year_id = ? and e.effective_from <= ?
				     and (e.effective_to is null or e.effective_to >= ?)
				left join section sec on sec.id = e.section_id left join school_class c on c.id = sec.class_id
				where s.status = 'ACTIVE'
				   or exists (select 1 from fee_line_status v where v.student_id = s.id and v.balance > 0)
				order by s.name""", (rs, i) -> new OfflineStudent(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getString(4), rs.getString(5), rs.getString(6), (Long) rs.getObject(7), rs.getLong(8), rs.getString(9),
				rs.getString(10), phones ? rs.getString(11) : null, rs.getString(12)),
				year.id(), Date.valueOf(on), Date.valueOf(on));

		Map<Long, List<OpenLine>> lines = new LinkedHashMap<>();
		jdbc.query("""
				select v.bill_line_id, v.bill_id, v.bill_no, v.student_id, s.name, s.gr_no, v.fee_head_id, h.name,
				       v.period_key, v.description, v.issue_date, v.due_date, v.net, v.adjusted, v.paid, v.balance, v.family_id
				from fee_line_status v join student s on s.id = v.student_id join fee_head h on h.id = v.fee_head_id
				where v.balance > 0
				order by v.family_id, v.due_date, v.bill_id, h.display_order, v.bill_line_id""", rs -> {
			lines.computeIfAbsent(rs.getLong(17), k -> new ArrayList<>()).add(new OpenLine(rs.getLong(1), rs.getLong(2),
					rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8),
					rs.getString(9), rs.getString(10), rs.getDate(11).toLocalDate(), rs.getDate(12).toLocalDate(),
					Amounts.text(rs.getBigDecimal(13)), Amounts.text(rs.getBigDecimal(14)), Amounts.text(rs.getBigDecimal(15)),
					Amounts.text(rs.getBigDecimal(16))));
		});
		Map<Long, BigDecimal> credits = new HashMap<>();
		jdbc.query("select family_id, sum(remaining) from payment_credit where remaining > 0 group by family_id",
				rs -> {
					credits.put(rs.getLong(1), rs.getBigDecimal(2));
				});

		List<OfflineFamily> families = new ArrayList<>();
		students.stream().map(OfflineStudent::familyId).distinct().forEach(familyId -> {
			List<OpenLine> open = lines.getOrDefault(familyId, List.of());
			BigDecimal owed = open.stream().map(l -> new BigDecimal(l.balance())).reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal credit = credits.getOrDefault(familyId, BigDecimal.ZERO);
			families.add(new OfflineFamily(familyId, Amounts.text(owed), Amounts.text(credit),
					Amounts.text(owed.subtract(credit)), open));
		});
		return new FeeSnapshot(clock.instant(), students, families);
	}
}
