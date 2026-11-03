package com.selectcode.sms.academics.api;

import com.selectcode.sms.academics.app.AcademicsService;
import com.selectcode.sms.academics.app.AcademicsService.ClassSubjectInput;
import com.selectcode.sms.academics.app.AcademicsService.ClassSubjectView;
import com.selectcode.sms.academics.app.AcademicsService.ClassView;
import com.selectcode.sms.academics.app.AcademicsService.SectionView;
import com.selectcode.sms.academics.app.AcademicsService.SubjectView;
import com.selectcode.sms.academics.domain.ActiveStatus;
import com.selectcode.sms.academics.domain.SchoolClass;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.usage.UsageContributor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
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
@RequestMapping("/api/v1/academics")
class AcademicsController {

	private static final String VIEW = "hasAuthority('" + Permissions.ACADEMICS_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.ACADEMICS_MANAGE + "')";

	record ClassRequest(@NotBlank @Size(max = 60) String name, @Size(max = 20) String shortName,
			SchoolClass.GradingMode gradingMode, Long version) {
	}

	record NameRequest(@NotBlank @Size(max = 80) String name, @Size(max = 20) String shortName, Long version) {
	}

	record ActiveStatusRequest(@NotNull ActiveStatus status) {
	}

	record ReorderRequest(@NotNull List<Long> classIds) {
	}

	record ClassSubjectRequest(@NotNull Long subjectId, @NotNull BigDecimal maxMarks, BigDecimal passingMarks) {
	}

	private final AcademicsService academics;

	AcademicsController(AcademicsService academics) {
		this.academics = academics;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/classes")
	List<ClassView> classes() {
		return academics.listClasses();
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/classes")
	@ResponseStatus(HttpStatus.CREATED)
	ClassView createClass(@Valid @RequestBody ClassRequest body) {
		return academics.createClass(body.name(), body.shortName(), body.gradingMode());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/classes/{id}")
	ClassView updateClass(@PathVariable long id, @Valid @RequestBody ClassRequest body) {
		return academics.updateClass(id, body.name(), body.shortName(), body.gradingMode(), version(body.version()));
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/classes/order")
	List<ClassView> reorder(@Valid @RequestBody ReorderRequest body) {
		return academics.reorder(body.classIds());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/classes/{id}/status")
	ClassView classStatus(@PathVariable long id, @Valid @RequestBody ActiveStatusRequest body) {
		return academics.setClassStatus(id, body.status());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/classes/{id}/usage")
	List<UsageContributor.Usage> classUsage(@PathVariable long id) {
		return academics.usages(UsageContributor.Target.CLASS, id);
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/classes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteClass(@PathVariable long id) {
		academics.deleteClass(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/classes/{classId}/sections")
	@ResponseStatus(HttpStatus.CREATED)
	SectionView createSection(@PathVariable long classId, @Valid @RequestBody NameRequest body) {
		return academics.createSection(classId, body.name());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/sections/{id}")
	SectionView renameSection(@PathVariable long id, @Valid @RequestBody NameRequest body) {
		return academics.renameSection(id, body.name(), version(body.version()));
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/sections/{id}/status")
	SectionView sectionStatus(@PathVariable long id, @Valid @RequestBody ActiveStatusRequest body) {
		return academics.setSectionStatus(id, body.status());
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/sections/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSection(@PathVariable long id) {
		academics.deleteSection(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/subjects")
	List<SubjectView> subjects() {
		return academics.listSubjects();
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/subjects")
	@ResponseStatus(HttpStatus.CREATED)
	SubjectView createSubject(@Valid @RequestBody NameRequest body) {
		return academics.createSubject(body.name(), body.shortName());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/subjects/{id}")
	SubjectView updateSubject(@PathVariable long id, @Valid @RequestBody NameRequest body) {
		return academics.updateSubject(id, body.name(), body.shortName(), version(body.version()));
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/subjects/{id}/status")
	SubjectView subjectStatus(@PathVariable long id, @Valid @RequestBody ActiveStatusRequest body) {
		return academics.setSubjectStatus(id, body.status());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/subjects/{id}/usage")
	List<UsageContributor.Usage> subjectUsage(@PathVariable long id) {
		return academics.usages(UsageContributor.Target.SUBJECT, id);
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/subjects/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSubject(@PathVariable long id) {
		academics.deleteSubject(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/years/{yearId}/classes/{classId}/subjects")
	List<ClassSubjectView> classSubjects(@PathVariable long yearId, @PathVariable long classId) {
		return academics.classSubjects(yearId, classId);
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/years/{yearId}/classes/{classId}/subjects")
	List<ClassSubjectView> saveClassSubjects(@PathVariable long yearId, @PathVariable long classId,
			@Valid @RequestBody List<@Valid ClassSubjectRequest> body) {
		return academics.saveClassSubjects(yearId, classId,
				body.stream().map(r -> new ClassSubjectInput(r.subjectId(), r.maxMarks(), r.passingMarks())).toList());
	}

	private static long version(Long version) {
		return version == null ? -1 : version;
	}
}
