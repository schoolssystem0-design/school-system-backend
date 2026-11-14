package com.selectcode.sms.exams.api;

import com.selectcode.sms.exams.app.ExamDocuments;
import com.selectcode.sms.exams.app.ExamSetupService;
import com.selectcode.sms.exams.app.ExamSetupService.BandInput;
import com.selectcode.sms.exams.app.ExamSetupService.ExamClassView;
import com.selectcode.sms.exams.app.ExamSetupService.ExamInput;
import com.selectcode.sms.exams.app.ExamSetupService.ExamView;
import com.selectcode.sms.exams.app.ExamSetupService.PaperInput;
import com.selectcode.sms.exams.app.ExamSetupService.ScaleView;
import com.selectcode.sms.exams.app.MarksService;
import com.selectcode.sms.exams.app.MarksService.MarkEntry;
import com.selectcode.sms.exams.app.MarksService.MarkGrid;
import com.selectcode.sms.exams.app.MarksService.MissingRow;
import com.selectcode.sms.exams.app.ResultMessages;
import com.selectcode.sms.exams.app.ResultMessages.ResultSmsReport;
import com.selectcode.sms.exams.app.ResultService;
import com.selectcode.sms.exams.app.ResultService.Analysis;
import com.selectcode.sms.exams.app.ResultService.ClassResults;
import com.selectcode.sms.exams.app.ResultService.CumulativeRow;
import com.selectcode.sms.exams.app.ResultService.RemarkInput;
import com.selectcode.sms.exams.app.ResultService.StudentExamRow;
import com.selectcode.sms.exams.app.ResultService.StudentResult;
import com.selectcode.sms.exams.app.ResultService.VersionRow;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Examinations and results (FR 14). Marks entry and a class's results follow the user's data scope (teachers: their own
 * subjects and sections, checked in the services); setting up exams, the workflow, analysis and printing need the
 * "all classes" scope.
 */
@RestController
@RequestMapping("/api/v1/exams")
class ExamsController {

	private static final String ALL = " and hasAuthority('SCOPE_ALL')";

	private static final String VIEW = "hasAuthority('" + Permissions.EXAMS_VIEW + "')";

	private static final String VIEW_ALL = VIEW + ALL;

	private static final String MANAGE = "hasAuthority('" + Permissions.EXAMS_MANAGE + "')" + ALL;

	private static final String ENTER = "hasAuthority('" + Permissions.EXAMS_MARKS_ENTER + "')";

	private static final String REVIEW = "hasAuthority('" + Permissions.EXAMS_REVIEW + "')" + ALL;

	private static final String PUBLISH = "hasAuthority('" + Permissions.EXAMS_PUBLISH + "')" + ALL;

	private static final String UNLOCK = "hasAuthority('" + Permissions.EXAMS_UNLOCK + "')" + ALL;

	private static final String PRINT = "hasAuthority('" + Permissions.EXAMS_PRINT + "')" + ALL;

	private static final String SEND = "hasAuthority('" + Permissions.EXAMS_RESULTS_SEND + "')" + ALL;

	record ExamRequest(@NotBlank @Size(max = 120) String name, LocalDate startDate, LocalDate endDate,
			@Size(max = 1000) String comments, Boolean countsInCumulative, String weight, Boolean visible, Long version) {

		ExamInput input() {
			return new ExamInput(name, startDate, endDate, comments, countsInCumulative, weight, visible);
		}
	}

	record ExamClassesRequest(@NotEmpty List<Long> classIds) {
	}

	record PaperRequest(@NotNull Long subjectId, @NotBlank String maxMarks, String passingMarks, LocalDate paperDate,
			LocalTime startTime, LocalTime endTime, @Size(max = 1000) String syllabus) {
	}

	record PapersRequest(@NotNull List<@Valid PaperRequest> papers) {
	}

	record BandRequest(@NotBlank @Size(max = 10) String grade, String minPercent, @Size(max = 100) String remark,
			Boolean passing) {
	}

	record BandsRequest(@NotEmpty List<@Valid BandRequest> bands) {
	}

	record ExamMarkRequest(@NotNull Long studentId, String obtained, String grade, String flag) {
	}

	record ExamMarksRequest(@NotNull List<@Valid ExamMarkRequest> marks) {
	}

	record ResultRemarkRequest(@NotNull Long studentId, @Size(max = 500) String teacherRemark,
			@Size(max = 500) String principalRemark) {
	}

	record ResultRemarksRequest(@NotNull List<@Valid ResultRemarkRequest> remarks) {
	}

	record UnlockRequest(@NotBlank @Size(max = 500) String reason) {
	}

	record ResultSmsRequest(Long sectionId, List<Long> studentIds) {
	}

	private final ExamSetupService setup;

	private final MarksService marks;

	private final ResultService results;

	private final ExamDocuments documents;

	private final ResultMessages messages;

	private final MessageOutbox outbox;

	ExamsController(ExamSetupService setup, MarksService marks, ResultService results, ExamDocuments documents,
			ResultMessages messages, MessageOutbox outbox) {
		this.setup = setup;
		this.marks = marks;
		this.results = results;
		this.documents = documents;
		this.messages = messages;
		this.outbox = outbox;
	}

	// ---------------------------------------------------------------- scales

	@PreAuthorize(VIEW)
	@GetMapping("/grade-scales")
	List<ScaleView> scales() {
		return setup.scales();
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/grade-scales/{id}")
	ScaleView saveBands(@PathVariable long id, @Valid @RequestBody BandsRequest body) {
		return setup.saveBands(id, body.bands()
			.stream()
			.map(b -> new BandInput(b.grade(), b.minPercent(), b.remark(), Boolean.TRUE.equals(b.passing())))
			.toList());
	}

	// ----------------------------------------------------------------- exams

	@PreAuthorize(VIEW)
	@GetMapping
	List<ExamView> exams() {
		return setup.exams();
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}")
	ExamView exam(@PathVariable long id) {
		return setup.exam(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ExamView create(@Valid @RequestBody ExamRequest body) {
		return setup.create(body.input());
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/{id}")
	ExamView update(@PathVariable long id, @Valid @RequestBody ExamRequest body) {
		return setup.update(id, body.input(), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/{id}/classes")
	ExamView addClasses(@PathVariable long id, @Valid @RequestBody ExamClassesRequest body) {
		return setup.addClasses(id, body.classIds());
	}

	// ----------------------------------------------------------- exam classes

	@PreAuthorize(VIEW)
	@GetMapping("/classes/{examClassId}")
	ExamClassView examClass(@PathVariable long examClassId) {
		return setup.examClass(examClassId);
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/classes/{examClassId}/papers")
	ExamClassView savePapers(@PathVariable long examClassId, @Valid @RequestBody PapersRequest body) {
		return setup.savePapers(examClassId, body.papers()
			.stream()
			.map(p -> new PaperInput(p.subjectId(), p.maxMarks(), p.passingMarks(), p.paperDate(), p.startTime(),
					p.endTime(), p.syllabus()))
			.toList());
	}

	// ------------------------------------------------------------------ marks

	@PreAuthorize(ENTER)
	@GetMapping("/classes/{examClassId}/marks")
	MarkGrid grid(@PathVariable long examClassId, @RequestParam long sectionId, @RequestParam long examSubjectId) {
		return marks.grid(examClassId, sectionId, examSubjectId);
	}

	@PreAuthorize(ENTER)
	@PutMapping("/classes/{examClassId}/marks")
	MarkGrid saveMarks(@PathVariable long examClassId, @RequestParam long sectionId, @RequestParam long examSubjectId,
			@Valid @RequestBody ExamMarksRequest body) {
		return marks.save(examClassId, sectionId, examSubjectId, body.marks()
			.stream()
			.map(m -> new MarkEntry(m.studentId(), m.obtained(), m.grade(), m.flag()))
			.toList());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/classes/{examClassId}/missing")
	List<MissingRow> missing(@PathVariable long examClassId) {
		return marks.missing(examClassId);
	}

	// ---------------------------------------------------------------- results

	/** The published snapshot (or a given version), otherwise a live preview. Teachers see their own sections. */
	@PreAuthorize(VIEW)
	@GetMapping("/classes/{examClassId}/results")
	ClassResults results(@PathVariable long examClassId, @RequestParam(required = false) Long sectionId,
			@RequestParam(required = false) Integer version) {
		return results.results(examClassId, sectionId, version);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/classes/{examClassId}/versions")
	List<VersionRow> versions(@PathVariable long examClassId) {
		return results.versions(examClassId);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/classes/{examClassId}/failed")
	List<StudentResult> failed(@PathVariable long examClassId) {
		return results.failed(examClassId);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/classes/{examClassId}/analysis")
	Analysis analysis(@PathVariable long examClassId) {
		return results.analysis(examClassId);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/cumulative")
	List<CumulativeRow> cumulative(@RequestParam long classId, @RequestParam long yearId) {
		return results.cumulative(classId, yearId);
	}

	@PreAuthorize(VIEW_ALL)
	@GetMapping("/students/{studentId}/results")
	List<StudentExamRow> studentResults(@PathVariable long studentId) {
		return results.studentResults(studentId);
	}

	/** Class teachers write their remarks; the overall (principal's) remark needs the review permission. */
	@PreAuthorize("hasAnyAuthority('" + Permissions.EXAMS_MARKS_ENTER + "', '" + Permissions.EXAMS_REVIEW + "')")
	@PutMapping("/classes/{examClassId}/remarks")
	ClassResults remarks(@PathVariable long examClassId, @Valid @RequestBody ResultRemarksRequest body) {
		boolean principal = CurrentActor.isSuperAdmin() || CurrentActor.hasPermission(Permissions.EXAMS_REVIEW)
				&& CurrentActor.hasPermission("SCOPE_ALL");
		return results.saveRemarks(examClassId, body.remarks()
			.stream()
			.map(r -> new RemarkInput(r.studentId(), r.teacherRemark(), r.principalRemark()))
			.toList(), principal);
	}

	// --------------------------------------------------------------- workflow

	@PreAuthorize(REVIEW)
	@PostMapping("/classes/{examClassId}/review")
	ExamClassView review(@PathVariable long examClassId) {
		return results.review(examClassId, true);
	}

	@PreAuthorize(REVIEW)
	@PostMapping("/classes/{examClassId}/back-to-draft")
	ExamClassView backToDraft(@PathVariable long examClassId) {
		return results.review(examClassId, false);
	}

	@PreAuthorize(PUBLISH)
	@PostMapping("/classes/{examClassId}/publish")
	ExamClassView publish(@PathVariable long examClassId) {
		return results.publish(examClassId);
	}

	@PreAuthorize(UNLOCK)
	@RequiresStepUp
	@PostMapping("/classes/{examClassId}/unlock")
	ExamClassView unlock(@PathVariable long examClassId, @Valid @RequestBody UnlockRequest body) {
		return results.unlock(examClassId, body.reason());
	}

	// -------------------------------------------------------------- documents

	@PreAuthorize(VIEW)
	@GetMapping(value = "/classes/{examClassId}/datesheet", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> datesheet(@PathVariable long examClassId) {
		return file(documents.datesheetPdf(examClassId), MediaType.APPLICATION_PDF, "datesheet.pdf");
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/classes/{examClassId}/datesheet.png", produces = MediaType.IMAGE_PNG_VALUE)
	ResponseEntity<byte[]> datesheetImage(@PathVariable long examClassId) {
		return file(documents.datesheetPng(examClassId), MediaType.IMAGE_PNG, "datesheet.png");
	}

	@PreAuthorize(PRINT)
	@GetMapping(value = "/classes/{examClassId}/admit-cards", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> admitCards(@PathVariable long examClassId, @RequestParam(required = false) Long sectionId,
			@RequestParam(defaultValue = "false") boolean withholdDefaulters) {
		return file(documents.admitCards(examClassId, sectionId, withholdDefaulters), MediaType.APPLICATION_PDF,
				"admit-cards.pdf");
	}

	@PreAuthorize(PRINT)
	@GetMapping(value = "/classes/{examClassId}/result-sheet", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> resultSheet(@PathVariable long examClassId, @RequestParam(required = false) Long sectionId) {
		return file(documents.resultSheet(examClassId, sectionId), MediaType.APPLICATION_PDF, "result-sheet.pdf");
	}

	@PreAuthorize(PRINT)
	@GetMapping(value = "/classes/{examClassId}/report-cards", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> reportCards(@PathVariable long examClassId, @RequestParam(required = false) Long sectionId,
			@RequestParam(required = false) Long studentId) {
		return file(documents.reportCards(examClassId, sectionId, studentId), MediaType.APPLICATION_PDF,
				"report-cards.pdf");
	}

	// -------------------------------------------------------------- messaging

	/** Requires an Idempotency-Key header; a retry never messages a family twice. */
	@PreAuthorize(SEND)
	@PostMapping("/classes/{examClassId}/send-results")
	ResultSmsReport send(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@PathVariable long examClassId, @Valid @RequestBody ResultSmsRequest body) {
		return messages.send(examClassId, body.sectionId(), body.studentIds(), key);
	}

	@PreAuthorize(SEND)
	@GetMapping("/messages")
	List<MessageOutbox.Queued> sent(@RequestParam(required = false) String status) {
		return outbox.list("RESULT", status, 200);
	}

	private static ResponseEntity<byte[]> file(byte[] bytes, MediaType type, String name) {
		return ResponseEntity.ok()
			.contentType(type)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
			.body(bytes);
	}
}
