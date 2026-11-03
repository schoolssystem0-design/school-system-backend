package com.selectcode.sms.school.app;

import com.selectcode.sms.school.domain.AcademicYear;
import com.selectcode.sms.school.infra.AcademicYearRepository;
import com.selectcode.sms.school.infra.WorkingDayStore;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academic years (FR 1.2, 9.1). Making a year current closes the previous current year, which then becomes read-only
 * except for authorised corrections (FR 1.3). Carrying classes, fees etc. forward is the rollover wizard (Phase 2).
 */
@Service
public class AcademicYearService implements AcademicYearLookup {

	private static final Pattern NAME = Pattern.compile("\\d{4}-\\d{4}");

	public record YearView(long id, String name, LocalDate startDate, LocalDate endDate, String status,
			Instant openedAt, Instant closedAt, long version) {

		static YearView of(AcademicYear y) {
			return new YearView(y.getId(), y.getName(), y.getStartDate(), y.getEndDate(), y.getStatus().name(),
					y.getOpenedAt(), y.getClosedAt(), y.getVersion());
		}
	}

	private final AcademicYearRepository years;

	private final WorkingDayStore workingDays;

	private final AuditService audit;

	private final Clock clock;

	AcademicYearService(AcademicYearRepository years, WorkingDayStore workingDays, AuditService audit, Clock clock) {
		this.years = years;
		this.workingDays = workingDays;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<YearView> list() {
		return years.findAllByOrderByStartDateDesc().stream().map(YearView::of).toList();
	}

	@Transactional
	public YearView create(String name, LocalDate start, LocalDate end) {
		String cleanName = validate(name, start, end, null);
		Optional<AcademicYear> previous = years.findAllByOrderByStartDateDesc()
			.stream()
			.filter(y -> y.getStartDate().isBefore(start))
			.findFirst();
		AcademicYear year = years.save(new AcademicYear(cleanName, start, end));
		// New years start with the previous year's working week (or Monday–Saturday).
		workingDays.save(year.getId(), previous.map(p -> workingDays.week(p.getId())).orElse(WorkingDayStore.defaultWeek()));
		audit.record("ACADEMIC_YEAR_CREATED", "academic_year", year.getId(), null,
				Map.of("name", cleanName, "startDate", start.toString(), "endDate", end.toString()));
		return YearView.of(year);
	}

	@Transactional
	public YearView update(long id, String name, LocalDate start, LocalDate end, long expectedVersion) {
		AcademicYear year = find(id);
		if (year.getVersion() != expectedVersion) {
			throw ApiException.conflict("CONFLICT_VERSION", "The year was changed by someone else. Reload and try again.");
		}
		if (year.getStatus() == AcademicYear.Status.CLOSED) {
			throw ApiException.conflict("YEAR_CLOSED", "A closed academic year cannot be changed");
		}
		var before = YearView.of(year);
		String cleanName = validate(name, start, end, id);
		year.change(cleanName, start, end, clock.instant());
		years.flush();
		audit.record("ACADEMIC_YEAR_UPDATED", "academic_year", id, before, YearView.of(year));
		return YearView.of(year);
	}

	/** Makes the year current; the previous current year is closed. */
	@Transactional
	public YearView makeCurrent(long id) {
		AcademicYear year = find(id);
		if (year.getStatus() == AcademicYear.Status.CURRENT) {
			return YearView.of(year);
		}
		if (year.getStatus() == AcademicYear.Status.CLOSED) {
			throw ApiException.conflict("YEAR_CLOSED", "A closed academic year cannot be reopened");
		}
		Instant now = clock.instant();
		Optional<AcademicYear> previous = years.findByStatus(AcademicYear.Status.CURRENT);
		previous.ifPresent(p -> {
			p.close(now);
			years.flush(); // free the "one current year" unique index before opening the new one
			audit.record("ACADEMIC_YEAR_CLOSED", "academic_year", p.getId(), null, Map.of("name", p.getName()));
		});
		year.makeCurrent(now);
		years.flush();
		audit.record("ACADEMIC_YEAR_OPENED", "academic_year", id,
				previous.map(p -> Map.<String, Object>of("previousCurrent", p.getName())).orElse(null),
				Map.of("current", year.getName()));
		return YearView.of(year);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<YearRef> findById(long id) {
		return years.findById(id).map(AcademicYearService::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<YearRef> current() {
		return years.findByStatus(AcademicYear.Status.CURRENT).map(AcademicYearService::ref);
	}

	AcademicYear find(long id) {
		return years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
	}

	private String validate(String name, LocalDate start, LocalDate end, Long selfId) {
		String clean = name == null ? "" : name.trim();
		if (!NAME.matcher(clean).matches()) {
			throw ApiException.businessRule("YEAR_NAME_INVALID", "Use the format 2026-2027");
		}
		if (start == null || end == null || !start.isBefore(end)) {
			throw ApiException.businessRule("YEAR_DATES_INVALID", "The start date must be before the end date");
		}
		if (end.isAfter(start.plusMonths(18))) {
			throw ApiException.businessRule("YEAR_TOO_LONG", "An academic year cannot be longer than 18 months");
		}
		for (AcademicYear other : years.findAll()) {
			if (selfId != null && other.getId().equals(selfId)) {
				continue;
			}
			if (other.getName().equalsIgnoreCase(clean)) {
				throw ApiException.conflict("YEAR_NAME_TAKEN", "Academic year " + clean + " already exists");
			}
			if (other.overlaps(start, end)) {
				throw ApiException.conflict("YEAR_OVERLAP", "The dates overlap academic year " + other.getName());
			}
		}
		return clean;
	}

	private static YearRef ref(AcademicYear y) {
		return new YearRef(y.getId(), y.getName(), y.getStartDate(), y.getEndDate(),
				AcademicYearLookup.Status.valueOf(y.getStatus().name()));
	}
}
