package com.selectcode.sms.school.app;

import com.selectcode.sms.school.domain.AcademicYear;
import com.selectcode.sms.school.domain.CalendarDayOff;
import com.selectcode.sms.school.infra.CalendarDayOffRepository;
import com.selectcode.sms.school.infra.WorkingDayStore;
import com.selectcode.sms.school.infra.WorkingDayStore.DayType;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.YearContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Working days and holidays (FR 1.4). Attendance registers and percentages count only working days, and the dashboard
 * does not warn about missing attendance on a holiday.
 */
@Service
public class CalendarService {

	public record DayOffView(long id, LocalDate dateFrom, LocalDate dateTo, String title, String type) {

		static DayOffView of(CalendarDayOff d) {
			return new DayOffView(d.getId(), d.getDateFrom(), d.getDateTo(), d.getTitle(), d.getType().name());
		}
	}

	private final AcademicYearService years;

	private final WorkingDayStore workingDays;

	private final CalendarDayOffRepository daysOff;

	private final YearContext yearContext;

	private final AuditService audit;

	CalendarService(AcademicYearService years, WorkingDayStore workingDays, CalendarDayOffRepository daysOff,
			YearContext yearContext, AuditService audit) {
		this.years = years;
		this.workingDays = workingDays;
		this.daysOff = daysOff;
		this.yearContext = yearContext;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public Map<DayOfWeek, DayType> week(long yearId) {
		years.find(yearId);
		return workingDays.week(yearId);
	}

	@Transactional
	public Map<DayOfWeek, DayType> saveWeek(long yearId, Map<DayOfWeek, DayType> week) {
		AcademicYear year = writableYear(yearId);
		Map<DayOfWeek, DayType> full = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek day : DayOfWeek.values()) {
			full.put(day, week.getOrDefault(day, DayType.OFF));
		}
		if (full.values().stream().allMatch(t -> t == DayType.OFF)) {
			throw ApiException.businessRule("NO_WORKING_DAYS", "At least one day must be a working day");
		}
		var before = workingDays.week(yearId);
		workingDays.save(yearId, full);
		audit.record("WORKING_WEEK_UPDATED", "academic_year", year.getId(), before, full);
		return full;
	}

	@Transactional(readOnly = true)
	public List<DayOffView> daysOff(long yearId) {
		years.find(yearId);
		return daysOff.findByAcademicYearIdOrderByDateFrom(yearId).stream().map(DayOffView::of).toList();
	}

	@Transactional
	public DayOffView addDayOff(long yearId, LocalDate from, LocalDate to, String title, CalendarDayOff.DayOffType type) {
		AcademicYear year = writableYear(yearId);
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.businessRule("DATES_INVALID", "The end date cannot be before the start date");
		}
		if (!year.contains(from) || !year.contains(to)) {
			throw ApiException.businessRule("DATES_OUTSIDE_YEAR", "The dates must be inside academic year " + year.getName());
		}
		if (title == null || title.isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Title is required");
		}
		CalendarDayOff saved = daysOff.save(new CalendarDayOff(yearId, from, to, title.trim(),
				type == null ? CalendarDayOff.DayOffType.HOLIDAY : type));
		audit.record("DAY_OFF_ADDED", "calendar_day_off", saved.getId(), null, DayOffView.of(saved));
		return DayOffView.of(saved);
	}

	@Transactional
	public void removeDayOff(long id) {
		CalendarDayOff day = daysOff.findById(id).orElseThrow(() -> ApiException.notFound("Holiday"));
		writableYear(day.getAcademicYearId());
		daysOff.delete(day);
		audit.record("DAY_OFF_REMOVED", "calendar_day_off", id, DayOffView.of(day), null);
	}

	/** Whether pupils are expected at school on this date (working weekday and not a holiday/vacation). */
	@Transactional(readOnly = true)
	public boolean isWorkingDay(long yearId, LocalDate date) {
		AcademicYear year = years.find(yearId);
		if (!year.contains(date) || workingDays.week(yearId).get(date.getDayOfWeek()) == DayType.OFF) {
			return false;
		}
		return daysOff.findOverlapping(date, date).stream().noneMatch(d -> d.getAcademicYearId() == yearId);
	}

	/** Number of working days between two dates (inclusive), e.g. for attendance percentages. */
	@Transactional(readOnly = true)
	public int countWorkingDays(long yearId, LocalDate from, LocalDate to) {
		AcademicYear year = years.find(yearId);
		Map<DayOfWeek, DayType> week = workingDays.week(yearId);
		List<CalendarDayOff> off = daysOff.findByAcademicYearIdOrderByDateFrom(yearId);
		int count = 0;
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			LocalDate day = d;
			if (year.contains(day) && week.get(day.getDayOfWeek()) != DayType.OFF
					&& off.stream().noneMatch(o -> o.covers(day))) {
				count++;
			}
		}
		return count;
	}

	private AcademicYear writableYear(long yearId) {
		AcademicYear year = years.find(yearId);
		yearContext.requireWritable(new AcademicYearLookup.YearRef(year.getId(), year.getName(), year.getStartDate(),
				year.getEndDate(), AcademicYearLookup.Status.valueOf(year.getStatus().name())));
		return year;
	}
}
