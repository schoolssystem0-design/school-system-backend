package com.selectcode.sms.office.api;

import com.selectcode.sms.office.app.ComplaintService;
import com.selectcode.sms.office.app.ComplaintService.ComplaintInput;
import com.selectcode.sms.office.app.ComplaintService.ComplaintView;
import com.selectcode.sms.office.app.OfficeBoardService;
import com.selectcode.sms.office.app.OfficeBoardService.BirthdayRow;
import com.selectcode.sms.office.app.OfficeBoardService.CircularInput;
import com.selectcode.sms.office.app.OfficeBoardService.CircularView;
import com.selectcode.sms.office.app.OfficeBoardService.EventInput;
import com.selectcode.sms.office.app.OfficeBoardService.EventView;
import com.selectcode.sms.office.app.OfficeBoardService.VisitorInput;
import com.selectcode.sms.office.app.OfficeBoardService.VisitorView;
import com.selectcode.sms.office.app.OfficeBoardService.WishResult;
import com.selectcode.sms.office.app.TaskService;
import com.selectcode.sms.office.app.TaskService.Colleague;
import com.selectcode.sms.office.app.TaskService.TaskInput;
import com.selectcode.sms.office.app.TaskService.TaskView;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Office tools (FR 3). Tasks are limited to the people involved (checked in the service). */
@RestController
@RequestMapping("/api/v1/office")
class OfficeController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String TASKS = "hasAuthority('" + Permissions.OFFICE_TASKS + "')";

	private static final String COMPLAINTS_VIEW = "hasAuthority('" + Permissions.OFFICE_COMPLAINTS_VIEW + "')" + ALL;

	private static final String COMPLAINTS_MANAGE = "hasAuthority('" + Permissions.OFFICE_COMPLAINTS_MANAGE + "')" + ALL;

	private static final String CIRCULARS_VIEW = "hasAuthority('" + Permissions.OFFICE_CIRCULARS_VIEW + "')";

	private static final String CIRCULARS_MANAGE = "hasAuthority('" + Permissions.OFFICE_CIRCULARS_MANAGE + "')" + ALL;

	private static final String EVENTS_VIEW = "hasAuthority('" + Permissions.OFFICE_EVENTS_VIEW + "')";

	private static final String EVENTS_MANAGE = "hasAuthority('" + Permissions.OFFICE_EVENTS_MANAGE + "')" + ALL;

	private static final String VISITORS_VIEW = "hasAuthority('" + Permissions.OFFICE_VISITORS_VIEW + "')" + ALL;

	private static final String VISITORS_MANAGE = "hasAuthority('" + Permissions.OFFICE_VISITORS_MANAGE + "')" + ALL;

	private static final String BIRTHDAYS = "hasAuthority('" + Permissions.STUDENTS_VIEW + "')" + ALL;

	private static final String WISH = "hasAuthority('" + Permissions.MESSAGES_SEND + "')" + ALL;

	record TaskRequest(@NotBlank @Size(max = 500) String description, LocalDate deadline, String priority,
			List<Long> shareWith) {
	}

	record TaskShareRequest(List<Long> userIds) {
	}

	record ComplaintRequest(Long familyId, @NotBlank @Size(max = 200) String complainant, @Size(max = 30) String phone,
			@NotBlank @Size(max = 200) String subject, @Size(max = 2000) String details) {
	}

	record ComplaintRemarkRequest(@NotBlank @Size(max = 1000) String text) {
	}

	record ComplaintAssignRequest(Long userId) {
	}

	record CircularRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 5000) String body, LocalDate date) {
	}

	record EventRequest(@NotBlank @Size(max = 200) String title, @NotNull LocalDate dateFrom, LocalDate dateTo, String type,
			@Size(max = 1000) String details, Boolean showOnWebsite) {
	}

	record VisitorRequest(LocalDate date, @NotBlank @Size(max = 200) String name, @Size(max = 300) String purpose,
			LocalTime timeIn) {
	}

	private final TaskService tasks;

	private final ComplaintService complaints;

	private final OfficeBoardService board;

	OfficeController(TaskService tasks, ComplaintService complaints, OfficeBoardService board) {
		this.tasks = tasks;
		this.complaints = complaints;
		this.board = board;
	}

	// ----------------------------------------------------------------- tasks

	@PreAuthorize("hasAnyAuthority('" + Permissions.OFFICE_TASKS + "', '" + Permissions.OFFICE_COMPLAINTS_MANAGE + "')")
	@GetMapping("/colleagues")
	List<Colleague> colleagues() {
		return tasks.colleagues();
	}

	@PreAuthorize(TASKS)
	@GetMapping("/tasks")
	List<TaskView> tasks(@RequestParam(defaultValue = "false") boolean includeDone) {
		return tasks.list(includeDone);
	}

	@PreAuthorize(TASKS)
	@PostMapping("/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	TaskView createTask(@Valid @RequestBody TaskRequest body) {
		return tasks.create(new TaskInput(body.description(), body.deadline(), body.priority(), body.shareWith()));
	}

	@PreAuthorize(TASKS)
	@PutMapping("/tasks/{id}/shares")
	TaskView share(@PathVariable long id, @Valid @RequestBody TaskShareRequest body) {
		return tasks.setShares(id, body.userIds());
	}

	@PreAuthorize(TASKS)
	@PostMapping("/tasks/{id}/complete")
	TaskView complete(@PathVariable long id) {
		return tasks.setDone(id, true);
	}

	@PreAuthorize(TASKS)
	@PostMapping("/tasks/{id}/reopen")
	TaskView reopenTask(@PathVariable long id) {
		return tasks.setDone(id, false);
	}

	// ------------------------------------------------------------ complaints

	@PreAuthorize(COMPLAINTS_VIEW)
	@GetMapping("/complaints")
	List<ComplaintView> complaints(@RequestParam(required = false) String status) {
		return complaints.list(status);
	}

	@PreAuthorize(COMPLAINTS_VIEW)
	@GetMapping("/complaints/{id}")
	ComplaintView complaint(@PathVariable long id) {
		return complaints.get(id);
	}

	@PreAuthorize(COMPLAINTS_MANAGE)
	@PostMapping("/complaints")
	@ResponseStatus(HttpStatus.CREATED)
	ComplaintView openComplaint(@Valid @RequestBody ComplaintRequest body) {
		return complaints.create(new ComplaintInput(body.familyId(), body.complainant(), body.phone(), body.subject(),
				body.details()));
	}

	@PreAuthorize(COMPLAINTS_MANAGE)
	@PostMapping("/complaints/{id}/remarks")
	ComplaintView remark(@PathVariable long id, @Valid @RequestBody ComplaintRemarkRequest body) {
		return complaints.addRemark(id, body.text());
	}

	@PreAuthorize(COMPLAINTS_MANAGE)
	@PostMapping("/complaints/{id}/assign")
	ComplaintView assign(@PathVariable long id, @Valid @RequestBody ComplaintAssignRequest body) {
		return complaints.assign(id, body.userId());
	}

	@PreAuthorize(COMPLAINTS_MANAGE)
	@PostMapping("/complaints/{id}/close")
	ComplaintView close(@PathVariable long id, @Valid @RequestBody ComplaintRemarkRequest body) {
		return complaints.setClosed(id, true, body.text());
	}

	@PreAuthorize(COMPLAINTS_MANAGE)
	@PostMapping("/complaints/{id}/reopen")
	ComplaintView reopenComplaint(@PathVariable long id, @Valid @RequestBody ComplaintRemarkRequest body) {
		return complaints.setClosed(id, false, body.text());
	}

	// ------------------------------------------------------------- circulars

	@PreAuthorize(CIRCULARS_VIEW)
	@GetMapping("/circulars")
	List<CircularView> circulars(@RequestParam(defaultValue = "100") int limit) {
		return board.circulars(limit);
	}

	@PreAuthorize(CIRCULARS_MANAGE)
	@PostMapping("/circulars")
	@ResponseStatus(HttpStatus.CREATED)
	CircularView postCircular(@Valid @RequestBody CircularRequest body) {
		return board.postCircular(new CircularInput(body.title(), body.body(), body.date()));
	}

	@PreAuthorize(CIRCULARS_MANAGE)
	@DeleteMapping("/circulars/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeCircular(@PathVariable long id) {
		board.removeCircular(id);
	}

	// ---------------------------------------------------------------- events

	@PreAuthorize(EVENTS_VIEW)
	@GetMapping("/events")
	List<EventView> events(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return board.events(from, to);
	}

	@PreAuthorize(EVENTS_MANAGE)
	@PostMapping("/events")
	@ResponseStatus(HttpStatus.CREATED)
	EventView addEvent(@Valid @RequestBody EventRequest body) {
		return board.addEvent(new EventInput(body.title(), body.dateFrom(), body.dateTo(), body.type(), body.details(),
				Boolean.TRUE.equals(body.showOnWebsite())));
	}

	@PreAuthorize(EVENTS_MANAGE)
	@DeleteMapping("/events/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeEvent(@PathVariable long id) {
		board.removeEvent(id);
	}

	// -------------------------------------------------------------- visitors

	@PreAuthorize(VISITORS_VIEW)
	@GetMapping("/visitors")
	List<VisitorView> visitors(@RequestParam LocalDate date) {
		return board.visitors(date);
	}

	@PreAuthorize(VISITORS_MANAGE)
	@PostMapping("/visitors")
	@ResponseStatus(HttpStatus.CREATED)
	VisitorView addVisitor(@Valid @RequestBody VisitorRequest body) {
		return board.addVisitor(new VisitorInput(body.date(), body.name(), body.purpose(), body.timeIn()));
	}

	// ------------------------------------------------------------- birthdays

	@PreAuthorize(BIRTHDAYS)
	@GetMapping("/birthdays")
	List<BirthdayRow> birthdays(@RequestParam(defaultValue = "30") int days) {
		return board.birthdays(Math.min(Math.max(days, 0), 366));
	}

	/** Safe to repeat: a person gets one wish a year. */
	@PreAuthorize(WISH)
	@PostMapping("/birthdays/{personType}/{id}/wish")
	WishResult wish(@PathVariable String personType, @PathVariable long id) {
		return board.wish(personType, id);
	}
}
