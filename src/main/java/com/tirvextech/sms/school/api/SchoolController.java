package com.selectcode.sms.school.api;

import com.selectcode.sms.school.app.AcademicYearService;
import com.selectcode.sms.school.app.AcademicYearService.YearView;
import com.selectcode.sms.school.app.CalendarService;
import com.selectcode.sms.school.app.SchoolProfileService;
import com.selectcode.sms.school.domain.CalendarDayOff;
import com.selectcode.sms.school.infra.SchoolProfileStore.Profile;
import com.selectcode.sms.school.infra.SchoolProfileStore.Signature;
import com.selectcode.sms.school.infra.WorkingDayStore.DayType;
import com.selectcode.sms.shared.security.AnyAuthenticated;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/school")
class SchoolController {

	record ProfileRequest(@NotBlank @Size(max = 200) String name, @Size(max = 200) String campusName,
			@Size(max = 500) String address, @Size(max = 30) String phonePrimary, @Size(max = 30) String phoneSecondary,
			@Email @Size(max = 254) String email, @Size(max = 200) String headName, @Size(max = 100) String headTitle,
			Long logoFileId, Long letterheadFileId, Long stampFileId, @Valid List<SignatureRequest> signatures,
			long version) {
	}

	record SignatureRequest(@NotBlank @Size(max = 60) String role, @NotBlank @Size(max = 200) String name,
			@Size(max = 100) String title, Long fileId) {
	}

	record YearRequest(@NotBlank @Size(max = 20) String name, @NotNull LocalDate startDate,
			@NotNull LocalDate endDate, Long version) {
	}

	record DayOffRequest(@NotNull LocalDate dateFrom, @NotNull LocalDate dateTo, @NotBlank @Size(max = 200) String title,
			CalendarDayOff.DayOffType type) {
	}

	private final SchoolProfileService profiles;

	private final AcademicYearService years;

	private final CalendarService calendar;

	private final com.selectcode.sms.school.app.RolloverService rollover;

	record RolloverRequest(@NotNull Long fromYearId, @NotNull java.util.Set<String> items) {
	}

	SchoolController(SchoolProfileService profiles, AcademicYearService years, CalendarService calendar,
			com.selectcode.sms.school.app.RolloverService rollover) {
		this.profiles = profiles;
		this.years = years;
		this.calendar = calendar;
		this.rollover = rollover;
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_YEAR_MANAGE + "')")
	@GetMapping("/rollover/options")
	List<com.selectcode.sms.school.app.RolloverService.Option> rolloverOptions() {
		return rollover.options();
	}

	/** Carry classes' subjects, teachers, working week… into a new year (FR 1.3). */
	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_YEAR_MANAGE + "')")
	@PostMapping("/years/{id}/rollover")
	com.selectcode.sms.school.app.RolloverService.RolloverResult rollover(@PathVariable long id,
			@Valid @RequestBody RolloverRequest body) {
		return rollover.rollover(body.fromYearId(), id, body.items());
	}

	/** Every signed-in user sees the school name and logo in the header. */
	@AnyAuthenticated
	@GetMapping("/profile")
	Profile profile() {
		return profiles.get();
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_PROFILE_EDIT + "')")
	@PutMapping("/profile")
	Profile updateProfile(@Valid @RequestBody ProfileRequest body) {
		List<Signature> signatures = body.signatures() == null ? List.of()
				: body.signatures().stream().map(s -> new Signature(s.role(), s.name(), s.title(), s.fileId())).toList();
		return profiles.update(new Profile(body.name(), body.campusName(), body.address(), body.phonePrimary(),
				body.phoneSecondary(), body.email(), body.headName(), body.headTitle(), body.logoFileId(),
				body.letterheadFileId(), body.stampFileId(), signatures, body.version()));
	}

	/** Year switcher in the header: every signed-in user. */
	@AnyAuthenticated
	@GetMapping("/years")
	List<YearView> years() {
		return years.list();
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_YEAR_MANAGE + "')")
	@PostMapping("/years")
	@ResponseStatus(HttpStatus.CREATED)
	YearView createYear(@Valid @RequestBody YearRequest body) {
		return years.create(body.name(), body.startDate(), body.endDate());
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_YEAR_MANAGE + "')")
	@PatchMapping("/years/{id}")
	YearView updateYear(@PathVariable long id, @Valid @RequestBody YearRequest body) {
		return years.update(id, body.name(), body.startDate(), body.endDate(),
				body.version() == null ? -1 : body.version());
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_YEAR_MANAGE + "')")
	@PostMapping("/years/{id}/make-current")
	YearView makeCurrent(@PathVariable long id) {
		return years.makeCurrent(id);
	}

	@AnyAuthenticated
	@GetMapping("/years/{id}/working-days")
	Map<DayOfWeek, DayType> workingDays(@PathVariable long id) {
		return calendar.week(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_CALENDAR_MANAGE + "')")
	@PutMapping("/years/{id}/working-days")
	Map<DayOfWeek, DayType> saveWorkingDays(@PathVariable long id, @RequestBody Map<DayOfWeek, DayType> week) {
		return calendar.saveWeek(id, week);
	}

	@AnyAuthenticated
	@GetMapping("/years/{id}/days-off")
	List<CalendarService.DayOffView> daysOff(@PathVariable long id) {
		return calendar.daysOff(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_CALENDAR_MANAGE + "')")
	@PostMapping("/years/{id}/days-off")
	@ResponseStatus(HttpStatus.CREATED)
	CalendarService.DayOffView addDayOff(@PathVariable long id, @Valid @RequestBody DayOffRequest body) {
		return calendar.addDayOff(id, body.dateFrom(), body.dateTo(), body.title(), body.type());
	}

	@PreAuthorize("hasAuthority('" + Permissions.SCHOOL_CALENDAR_MANAGE + "')")
	@DeleteMapping("/days-off/{dayOffId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeDayOff(@PathVariable long dayOffId) {
		calendar.removeDayOff(dayOffId);
	}
}
