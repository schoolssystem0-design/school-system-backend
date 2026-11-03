package com.selectcode.sms.attendance.api;

import com.selectcode.sms.attendance.app.AttendanceReports;
import com.selectcode.sms.attendance.app.AttendanceReports.RepeatedAbsence;
import com.selectcode.sms.attendance.app.AttendanceReports.SectionSummary;
import com.selectcode.sms.attendance.app.AttendanceReports.StudentFigures;
import com.selectcode.sms.attendance.app.AttendanceReports.StudentMonth;
import com.selectcode.sms.attendance.app.LeaveService;
import com.selectcode.sms.attendance.app.LeaveService.LeaveInput;
import com.selectcode.sms.attendance.app.LeaveService.LeaveView;
import com.selectcode.sms.attendance.app.RegisterService;
import com.selectcode.sms.attendance.app.RegisterService.BulkInput;
import com.selectcode.sms.attendance.app.RegisterService.BulkResult;
import com.selectcode.sms.attendance.app.RegisterService.CorrectionRow;
import com.selectcode.sms.attendance.app.RegisterService.Mark;
import com.selectcode.sms.attendance.app.RegisterService.MissingRegister;
import com.selectcode.sms.attendance.app.RegisterService.RegisterView;
import com.selectcode.sms.attendance.app.StaffAttendanceService;
import com.selectcode.sms.attendance.app.StaffAttendanceService.StaffMonthRow;
import com.selectcode.sms.attendance.app.StaffAttendanceService.StaffDay;
import com.selectcode.sms.attendance.app.StaffAttendanceService.StaffMark;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import com.selectcode.sms.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/**
 * Attendance (FR 13). Registers and their reports follow the user's data scope (teachers: their own sections, checked
 * in the services); leave, staff attendance, corrections and school-wide summaries need the "all classes" scope.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String VIEW = "hasAuthority('" + Permissions.ATTENDANCE_VIEW + "')";

	private static final String MARK = "hasAuthority('" + Permissions.ATTENDANCE_MARK + "')";

	private static final String CORRECT = "hasAuthority('" + Permissions.ATTENDANCE_CORRECT + "')" + ALL;

	private static final String VIEW_ALL = VIEW + ALL;

	private static final String LEAVE = "hasAuthority('" + Permissions.ATTENDANCE_LEAVE_MANAGE + "')" + ALL;

	private static final String LEAVE_APPROVE = "hasAuthority('" + Permissions.ATTENDANCE_LEAVE_APPROVE + "')" + ALL;

	private static final String STAFF_MARK = "hasAuthority('" + Permissions.ATTENDANCE_STAFF_MARK + "')" + ALL;

	private static final String STAFF_READ = "hasAnyAuthority('" + Permissions.ATTENDANCE_STAFF_MARK + "', '"
			+ Permissions.ATTENDANCE_STAFF_APPROVE + "', '" + Permissions.PAYROLL_VIEW + "')" + ALL;

	private static final String STAFF_APPROVE = "hasAuthority('" + Permissions.ATTENDANCE_STAFF_APPROVE + "')" + ALL;

	record MarkRequest(@NotNull Long studentId, @NotBlank String status, LocalTime arrivalTime,
			@Size(max = 300) String note) {
	}

	record RegisterRequest(List<@Valid MarkRequest> marks, @Size(max = 500) String reason) {
	}

	record BulkRequest(@NotNull LocalDate from, @NotNull LocalDate to, Long classId, Long sectionId, String fromStatus,
			@NotBlank String toStatus, Boolean fillUnmarked, @NotBlank @Size(max = 500) String reason) {
	}

	record LeaveApplicationRequest(@NotBlank String personType, Long studentId, Long staffId, @NotBlank String leaveType,
			@NotNull LocalDate dateFrom, @NotNull LocalDate dateTo, @NotBlank @Size(max = 500) String reason) {
	}

	record LeaveDecisionRequest(@Size(max = 500) String note) {
	}

	record StaffMarkRequest(@NotNull Long staffId, @NotBlank String status, LocalTime arrivalTime,
			@Size(max = 300) String note) {
	}

	record StaffDayRequest(List<@Valid StaffMarkRequest> marks, @Size(max = 500) String reason) {
	}

	record MonthApproveRequest(@NotBlank String month, List<Long> staffIds, Boolean unmarkedAsPresent) {
	}

	record MonthReopenRequest(@NotBlank String month, @NotBlank @Size(max = 500) String reason) {
	}

	private final RegisterService registers;

	private final AttendanceReports reports;

	private final LeaveService leave;

	private final StaffAttendanceService staff;

	AttendanceController(RegisterService registers, AttendanceReports reports, LeaveService leave,
			StaffAttendanceService staff) {
		this.registers = registers;
		this.reports = reports;
		this.leave = leave;
		this.staff = staff;
	}

	// ------------------------------------------------------------ registers

	@PreAuthorize(VIEW)
	@GetMapping("/registers/{sectionId}")
	RegisterView register(@PathVariable long sectionId, @RequestParam LocalDate date) {
		return registers.register(sectionId, date);
	}

	@PreAuthorize(MARK)
	@PutMapping("/registers/{sectionId}")
	RegisterView save(@PathVariable long sectionId, @RequestParam LocalDate date,
			@Valid @RequestBody RegisterRequest body) {
		return registers.save(sectionId, date, body.marks() == null ? List.of()
				: body.marks().stream().map(m -> new Mark(m.studentId(), m.status(), m.arrivalTime(), m.note())).toList(),
				body.reason());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/missing")
	List<MissingRegister> missing(@RequestParam LocalDate date) {
		return registers.missing(date);
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/registers/{sectionId}/monthly", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> monthly(@PathVariable long sectionId, @RequestParam String month) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("attendance-register.pdf").build().toString())
			.body(reports.monthlyRegister(sectionId, month(month)));
	}

	@PreAuthorize(CORRECT)
	@PostMapping("/corrections/bulk")
	BulkResult bulk(@Valid @RequestBody BulkRequest body) {
		return registers.bulk(new BulkInput(body.from(), body.to(), body.classId(), body.sectionId(), body.fromStatus(),
				body.toStatus(), Boolean.TRUE.equals(body.fillUnmarked()), body.reason()));
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/corrections")
	List<CorrectionRow> corrections(@RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(required = false) String personType, @RequestParam(required = false) Long personId) {
		return registers.corrections(from, to, personType, personId);
	}

	// -------------------------------------------------------------- reports

	@PreAuthorize(VIEW)
	@GetMapping("/reports/students")
	List<StudentFigures> students(@RequestParam(required = false) Long sectionId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return reports.figures(sectionId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/reports/low")
	List<StudentFigures> low(@RequestParam(required = false) Long sectionId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return reports.low(sectionId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/reports/full")
	List<StudentFigures> full(@RequestParam(required = false) Long sectionId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return reports.fullAttendance(sectionId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/reports/repeated")
	List<RepeatedAbsence> repeated(@RequestParam LocalDate asOf) {
		return reports.repeatedAbsences(asOf);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/reports/sections")
	List<SectionSummary> sections(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return reports.sectionSummaries(from, to);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/students/{studentId}/months")
	List<StudentMonth> studentMonths(@PathVariable long studentId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return reports.studentMonths(studentId, from, to);
	}

	// ---------------------------------------------------------------- leave

	@PreAuthorize("(hasAuthority('" + Permissions.ATTENDANCE_VIEW + "') or hasAuthority('"
			+ Permissions.ATTENDANCE_LEAVE_MANAGE + "'))" + ALL)
	@GetMapping("/leave")
	List<LeaveView> leaveList(@RequestParam(required = false) String status,
			@RequestParam(required = false) String personType, @RequestParam(required = false) Long personId,
			@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		return leave.list(status, personType, personId, from, to);
	}

	@PreAuthorize(LEAVE)
	@PostMapping("/leave")
	@ResponseStatus(HttpStatus.CREATED)
	LeaveView createLeave(@Valid @RequestBody LeaveApplicationRequest body) {
		return leave.create(new LeaveInput(body.personType(), body.studentId(), body.staffId(), body.leaveType(),
				body.dateFrom(), body.dateTo(), body.reason()));
	}

	@PreAuthorize(LEAVE_APPROVE)
	@PostMapping("/leave/{id}/approve")
	LeaveView approveLeave(@PathVariable long id, @Valid @RequestBody(required = false) LeaveDecisionRequest body) {
		return leave.decide(id, true, body == null ? null : body.note());
	}

	@PreAuthorize(LEAVE_APPROVE)
	@PostMapping("/leave/{id}/reject")
	LeaveView rejectLeave(@PathVariable long id, @Valid @RequestBody LeaveDecisionRequest body) {
		return leave.decide(id, false, body.note());
	}

	@PreAuthorize(LEAVE)
	@PostMapping("/leave/{id}/cancel")
	LeaveView cancelLeave(@PathVariable long id, @Valid @RequestBody LeaveDecisionRequest body) {
		return leave.cancel(id, body.note());
	}

	// ---------------------------------------------------------------- staff

	@PreAuthorize(STAFF_READ)
	@GetMapping("/staff/day")
	StaffDay staffDay(@RequestParam LocalDate date) {
		return staff.day(date);
	}

	@PreAuthorize(STAFF_MARK)
	@PutMapping("/staff/day")
	StaffDay saveStaffDay(@RequestParam LocalDate date, @Valid @RequestBody StaffDayRequest body) {
		return staff.save(date, body.marks() == null ? List.of()
				: body.marks().stream().map(m -> new StaffMark(m.staffId(), m.status(), m.arrivalTime(), m.note())).toList(),
				body.reason());
	}

	@PreAuthorize(STAFF_READ)
	@GetMapping("/staff/month")
	List<StaffMonthRow> staffMonth(@RequestParam String month) {
		return staff.month(month(month));
	}

	@PreAuthorize(STAFF_APPROVE)
	@RequiresStepUp
	@PostMapping("/staff/month/approve")
	List<StaffMonthRow> approveMonth(@Valid @RequestBody MonthApproveRequest body) {
		return staff.approve(month(body.month()), body.staffIds(), Boolean.TRUE.equals(body.unmarkedAsPresent()));
	}

	@PreAuthorize(STAFF_APPROVE)
	@RequiresStepUp
	@PostMapping("/staff/month/reopen")
	List<StaffMonthRow> reopenMonth(@Valid @RequestBody MonthReopenRequest body) {
		return staff.reopen(month(body.month()), body.reason());
	}

	private static YearMonth month(String raw) {
		try {
			return YearMonth.parse(raw.trim());
		}
		catch (RuntimeException e) {
			throw ApiException.businessRule("MONTH_INVALID", "The month must look like 2026-10");
		}
	}
}
