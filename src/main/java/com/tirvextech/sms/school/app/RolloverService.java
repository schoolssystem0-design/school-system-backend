package com.selectcode.sms.school.app;

import com.selectcode.sms.school.infra.WorkingDayStore;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.RolloverStep;
import com.selectcode.sms.shared.year.YearContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Open a new academic year" wizard (FR 1.3): copy the chosen items from the previous year without retyping and
 * without touching the old year. Student promotion is the next step (Students → Promotion).
 */
@Service
public class RolloverService {

	public record Option(String key, String label) {
	}

	public record RolloverResult(Map<String, Integer> copied) {
	}

	private final AcademicYearService years;

	private final WorkingDayStore workingDays;

	private final List<RolloverStep> steps;

	private final YearContext yearContext;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	RolloverService(AcademicYearService years, WorkingDayStore workingDays, List<RolloverStep> steps,
			YearContext yearContext, AuditService audit, JdbcTemplate jdbc, JsonMapper json) {
		this.years = years;
		this.workingDays = workingDays;
		this.steps = steps;
		this.yearContext = yearContext;
		this.audit = audit;
		this.jdbc = jdbc;
		this.json = json;
	}

	public List<Option> options() {
		List<Option> list = new java.util.ArrayList<>();
		list.add(new Option("WORKING_WEEK", "Working days of the week"));
		steps.forEach(s -> list.add(new Option(s.key(), s.label())));
		return list;
	}

	@Transactional
	public RolloverResult rollover(long fromYearId, long toYearId, Set<String> chosen) {
		YearRef from = years.findById(fromYearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		YearRef to = years.findById(toYearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		if (!to.startDate().isAfter(from.startDate())) {
			throw ApiException.businessRule("YEAR_ORDER", "Copy from an earlier year into a later one");
		}
		yearContext.requireWritable(to);
		Map<String, Integer> copied = new LinkedHashMap<>();
		if (chosen.contains("WORKING_WEEK")) {
			workingDays.save(to.id(), workingDays.week(from.id()));
			copied.put("WORKING_WEEK", 7);
		}
		for (RolloverStep step : steps) {
			if (chosen.contains(step.key())) {
				copied.put(step.key(), step.copy(from, to));
			}
		}
		jdbc.update("""
				insert into year_rollover (school_id, from_year_id, to_year_id, items, performed_by)
				values (?, ?, ?, ?::jsonb, ?)""", TenantContext.requireSchoolId(), from.id(), to.id(),
				json.writeValueAsString(copied), CurrentActor.require().displayName());
		audit.record("YEAR_ROLLOVER", "academic_year", to.id(), Map.of("from", from.name()), copied);
		return new RolloverResult(copied);
	}
}
