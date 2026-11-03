package com.selectcode.sms.exams.api;

import com.selectcode.sms.exams.app.TestMarksService;
import com.selectcode.sms.exams.app.TestMarksService.ClassTestInput;
import com.selectcode.sms.exams.app.TestMarksService.ClassTestView;
import com.selectcode.sms.exams.app.TestMarksService.OverallRow;
import com.selectcode.sms.exams.app.TestMarksService.StudentTestRow;
import com.selectcode.sms.exams.app.TestMarksService.TestMarkEntry;
import com.selectcode.sms.exams.app.TestMarksService.TestSheet;
import com.selectcode.sms.shared.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

/** Simple test marks and positions (D-51). Teachers' subjects and sections are checked in the service. */
@RestController
@RequestMapping("/api/v1/test-marks")
class TestMarksController {

	private static final String VIEW = "hasAuthority('" + Permissions.EXAMS_VIEW + "')";

	private static final String ENTER = "hasAuthority('" + Permissions.EXAMS_MARKS_ENTER + "')";

	record ClassTestRequest(@NotNull Long sectionId, @NotNull Long subjectId, @NotBlank @Size(max = 100) String name,
			@NotNull LocalDate testDate, @NotBlank String maxMarks) {
	}

	record TestMarkRequest(@NotNull Long studentId, String obtained, Boolean absent) {
	}

	record TestMarksRequest(@NotNull List<@Valid TestMarkRequest> marks, @Size(max = 500) String reason) {
	}

	private final TestMarksService tests;

	TestMarksController(TestMarksService tests) {
		this.tests = tests;
	}

	@PreAuthorize(VIEW)
	@GetMapping
	List<ClassTestView> list(@RequestParam long sectionId) {
		return tests.list(sectionId);
	}

	@PreAuthorize(ENTER)
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TestSheet create(@Valid @RequestBody ClassTestRequest body) {
		return tests.create(new ClassTestInput(body.sectionId(), body.subjectId(), body.name(), body.testDate(),
				body.maxMarks()));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}")
	TestSheet sheet(@PathVariable long id) {
		return tests.sheet(id);
	}

	@PreAuthorize(ENTER)
	@PutMapping("/{id}/marks")
	TestSheet save(@PathVariable long id, @Valid @RequestBody TestMarksRequest body) {
		return tests.save(id, body.marks()
			.stream()
			.map(m -> new TestMarkEntry(m.studentId(), m.obtained(), Boolean.TRUE.equals(m.absent())))
			.toList(), body.reason());
	}

	@PreAuthorize(ENTER)
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long id) {
		tests.delete(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/overall")
	List<OverallRow> overall(@RequestParam long sectionId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
		return tests.overall(sectionId, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/students/{studentId}")
	List<StudentTestRow> forStudent(@PathVariable long studentId) {
		return tests.forStudent(studentId);
	}
}
