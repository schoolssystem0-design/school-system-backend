package com.selectcode.sms.reports.api;

import com.selectcode.sms.reports.app.CrossSchoolReports;
import com.selectcode.sms.reports.app.CrossSchoolReports.CrossSchoolReport;
import com.selectcode.sms.reports.app.ReportDoc;
import com.selectcode.sms.reports.app.ReportFiles;
import com.selectcode.sms.reports.app.ReportFiles.ExportTable;
import com.selectcode.sms.reports.app.ReportService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Weekly and monthly reports (FR 15) and the super admin's cross-school report (P-9). The data scope of a report is
 * checked in the service; a teacher's report contains only the sections they teach and never fee figures.
 */
@RestController
class ReportsController {

	private static final String VIEW = "hasAuthority('" + Permissions.REPORTS_VIEW + "')";

	private static final String STAFF = "hasAuthority('" + Permissions.REPORTS_STAFF_VIEW + "') and hasAuthority('SCOPE_ALL')";

	private static final String SUPER_ADMIN = "hasAuthority('" + CurrentActor.SUPER_ADMIN_AUTHORITY + "')";

	private static final MediaType XLSX = MediaType
		.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	record TableRequest(@Size(max = 200) String title, @NotNull List<String> columns, @NotNull List<List<String>> rows) {
	}

	record TableExportRequest(@NotBlank @Size(max = 200) String title, List<String> filters,
			@NotEmpty List<@Valid TableRequest> tables) {
	}

	private final ReportService reports;

	private final ReportFiles files;

	private final CrossSchoolReports crossSchool;

	ReportsController(ReportService reports, ReportFiles files, CrossSchoolReports crossSchool) {
		this.reports = reports;
		this.files = files;
		this.crossSchool = crossSchool;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/api/v1/reports/student")
	ReportDoc student(@RequestParam long studentId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
		return reports.student(studentId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/api/v1/reports/student/file")
	ResponseEntity<byte[]> studentFile(@RequestParam long studentId, @RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam String format) {
		return file(files.render(reports.student(studentId, from, to), format), "student-report", format);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/api/v1/reports/class")
	ReportDoc classReport(@RequestParam(required = false) Long classId, @RequestParam(required = false) Long sectionId,
			@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return reports.classReport(classId, sectionId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/api/v1/reports/class/file")
	ResponseEntity<byte[]> classFile(@RequestParam(required = false) Long classId, @RequestParam(required = false) Long sectionId,
			@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam String format) {
		return file(files.render(reports.classReport(classId, sectionId, from, to), format), "class-report", format);
	}

	@PreAuthorize(STAFF)
	@GetMapping("/api/v1/reports/teacher")
	ReportDoc teacher(@RequestParam long staffId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
		return reports.teacher(staffId, from, to);
	}

	@PreAuthorize(STAFF)
	@GetMapping("/api/v1/reports/teacher/file")
	ResponseEntity<byte[]> teacherFile(@RequestParam long staffId, @RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam String format) {
		return file(files.render(reports.teacher(staffId, from, to), format), "teacher-report", format);
	}

	/** Any signed-in school user: it only returns the tables the screen already shows them, as a file. */
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/api/v1/reports/tables/file")
	ResponseEntity<byte[]> tablesFile(@RequestParam String format, @Valid @RequestBody TableExportRequest body) {
		return file(files.tables(body.title(), body.filters() == null ? List.of() : body.filters(),
				body.tables().stream().map(t -> new ExportTable(t.title(), t.columns(), t.rows())).toList(), format), "report", format);
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/api/v1/platform/reports/schools")
	CrossSchoolReport schools(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return crossSchool.report(from, to);
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/api/v1/platform/reports/schools/file")
	ResponseEntity<byte[]> schoolsFile(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam String format) {
		return file(crossSchool.export(from, to, format), "all-schools-report", format);
	}

	private static ResponseEntity<byte[]> file(byte[] bytes, String name, String format) {
		boolean pdf = "pdf".equals(format);
		return ResponseEntity.ok()
			.contentType(pdf ? MediaType.APPLICATION_PDF : XLSX)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(name + (pdf ? ".pdf" : ".xlsx")).build().toString())
			.body(bytes);
	}
}
