package com.tirvextech.sms.academics.api;

import com.tirvextech.sms.academics.app.BellSchedule;
import com.tirvextech.sms.academics.app.TeachingService;
import com.tirvextech.sms.academics.app.TimetableGenerator;
import com.tirvextech.sms.academics.app.TeachingService.AssignmentView;
import com.tirvextech.sms.academics.app.TeachingService.Period;
import com.tirvextech.sms.academics.app.TeachingService.PeriodView;
import com.tirvextech.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/academics/years/{yearId}")
class TeachingController {

	private static final String VIEW = "hasAuthority('" + Permissions.ACADEMICS_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.ACADEMICS_MANAGE + "')";

	record AssignRequest(@NotNull Long sectionId, Long subjectId, @NotNull Long staffId, LocalDate effectiveFrom) {
	}

	record EndRequest(LocalDate effectiveTo) {
	}

	record HandoverRequest(@NotNull Long fromStaffId, @NotNull Long toStaffId, LocalDate effectiveFrom) {
	}

	record PeriodRequest(int weekday, int periodNo, LocalTime startTime, LocalTime endTime, Long subjectId,
			Long staffId, Boolean locked) {

		Period toPeriod() {
			return new Period(weekday, periodNo, startTime, endTime, subjectId, staffId, Boolean.TRUE.equals(locked));
		}
	}

	record BellSlotRequest(@NotNull String kind, String label, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
			Boolean onHalfDay) {
	}

	/** sectionIds empty or missing: every section. */
	record GenerateRequest(List<Long> sectionIds) {
	}

	record SectionTimetableRequest(@NotNull Long sectionId, @NotNull List<PeriodRequest> periods) {
	}

	private final TeachingService teaching;

	private final BellSchedule bells;

	private final TimetableGenerator generator;

	TeachingController(TeachingService teaching, BellSchedule bells, TimetableGenerator generator) {
		this.teaching = teaching;
		this.bells = bells;
		this.generator = generator;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/assignments")
	List<AssignmentView> assignments(@PathVariable long yearId, @RequestParam(required = false) Long sectionId,
			@RequestParam(required = false) Long staffId, @RequestParam(defaultValue = "true") boolean currentOnly) {
		return teaching.assignments(yearId, sectionId, staffId, currentOnly);
	}

	/** Class teacher (no subject) or subject teacher of a section. */
	@PreAuthorize(MANAGE)
	@PostMapping("/assignments")
	@ResponseStatus(HttpStatus.CREATED)
	AssignmentView assign(@PathVariable long yearId, @Valid @RequestBody AssignRequest body) {
		return teaching.assign(yearId, body.sectionId(), body.subjectId(), body.staffId(), body.effectiveFrom());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/assignments/{assignmentId}/end")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void end(@PathVariable long yearId, @PathVariable long assignmentId, @RequestBody(required = false) EndRequest body) {
		teaching.endAssignment(assignmentId, body == null ? null : body.effectiveTo());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/assignments/handover")
	Map<String, Integer> handover(@PathVariable long yearId, @Valid @RequestBody HandoverRequest body) {
		return Map.of("moved", teaching.handover(yearId, body.fromStaffId(), body.toStaffId(), body.effectiveFrom()));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/timetable")
	List<PeriodView> timetable(@PathVariable long yearId, @RequestParam(required = false) Long sectionId,
			@RequestParam(required = false) Long staffId) {
		return teaching.timetable(yearId, sectionId, staffId);
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/sections/{sectionId}/timetable")
	List<PeriodView> saveTimetable(@PathVariable long yearId, @PathVariable long sectionId,
			@Valid @RequestBody List<PeriodRequest> body) {
		return teaching.saveTimetable(yearId, sectionId, body.stream().map(PeriodRequest::toPeriod).toList());
	}

	/** The school day: periods and breaks with their times. */
	@PreAuthorize(VIEW)
	@GetMapping("/bell-schedule")
	List<BellSchedule.Slot> bellSchedule(@PathVariable long yearId) {
		return bells.slots(yearId);
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/bell-schedule")
	List<BellSchedule.Slot> saveBellSchedule(@PathVariable long yearId, @Valid @RequestBody List<@Valid BellSlotRequest> body) {
		return bells.save(yearId, body.stream()
			.map(s -> new BellSchedule.Slot(s.kind(), s.label(), s.startTime(), s.endTime(), !Boolean.FALSE.equals(s.onHalfDay()),
					null))
			.toList());
	}

	/** Proposes a timetable for the chosen sections (or all); nothing is saved. */
	@PreAuthorize(MANAGE)
	@PostMapping("/timetable/generate")
	TimetableGenerator.Proposal generate(@PathVariable long yearId, @RequestBody(required = false) GenerateRequest body) {
		return generator.generate(yearId, body == null ? null : body.sectionIds());
	}

	/** Saves the timetables of several sections at once (a generated proposal, after the user has looked at it). */
	@PreAuthorize(MANAGE)
	@PutMapping("/timetable")
	Map<String, Integer> saveTimetables(@PathVariable long yearId,
			@Valid @RequestBody List<@Valid SectionTimetableRequest> body) {
		Map<Long, List<Period>> bySection = new java.util.LinkedHashMap<>();
		for (SectionTimetableRequest s : body) {
			bySection.put(s.sectionId(), s.periods().stream().map(PeriodRequest::toPeriod).toList());
		}
		return Map.of("periods", teaching.saveTimetables(yearId, bySection));
	}
}
