package com.selectcode.sms.admissions.api;

import com.selectcode.sms.students.StudentDetails;
import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.admissions.app.AdmissionDocuments;
import com.selectcode.sms.admissions.app.AdmissionsService;
import com.selectcode.sms.admissions.app.AdmissionsService.EnquiryRow;
import com.selectcode.sms.admissions.app.AdmissionsService.EnquiryView;
import com.selectcode.sms.admissions.app.AdmissionsService.Match;
import com.selectcode.sms.admissions.app.AdmissionsService.Remark;
import com.selectcode.sms.admissions.app.EnquiryStatuses;
import com.selectcode.sms.admissions.domain.Enquiry.EnquiryDetails;
import com.selectcode.sms.admissions.domain.Enquiry.TestResult;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.students.StudentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admissions")
class AdmissionsController {

	private static final String VIEW = "hasAuthority('" + Permissions.ADMISSIONS_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.ADMISSIONS_MANAGE + "')";

	record EnquiryRequest(LocalDate enquiryDate, String source, @NotBlank @Size(max = 200) String childName,
			String gender, LocalDate dob, Long classAppliedId, @Size(max = 200) String parentName,
			@Size(max = 30) String parentPhone, @Size(max = 20) String parentCnic, @Size(max = 500) String address,
			@Size(max = 200) String previousSchool, @Size(max = 1000) String remarks, Long version) {

		EnquiryDetails details() {
			return new EnquiryDetails(enquiryDate, source, childName, gender, dob, classAppliedId, null, parentName,
					parentPhone, parentCnic, address, previousSchool, remarks);
		}
	}

	record EnquiryStatusRequest(@NotNull Long statusId, @Size(max = 1000) String remark) {
	}

	record RemarkRequest(@NotBlank @Size(max = 1000) String text) {
	}

	record TestRequest(LocalDate testDate, BigDecimal obtained, BigDecimal total, @Size(max = 500) String remark) {
	}

	record StatusDefinition(@NotBlank @Size(max = 60) String name, @Size(max = 7) String colour, Boolean active) {
	}

	record EnrolStudent(@NotBlank @Size(max = 200) String name, @NotBlank String gender, LocalDate dob,
			@Size(max = 100) String placeOfBirth, @Size(max = 20) String bFormNo, @Size(max = 40) String religion,
			@Size(max = 40) String nationality, @Size(max = 30) String phone, @Size(max = 500) String address,
			@Size(max = 200) String previousSchool, @Size(max = 40) String studyGroup, Long photoFileId) {
	}

	record EnrolFamily(@Size(max = 200) String fatherName, @Size(max = 20) String fatherCnic,
			@Size(max = 30) String fatherPhone, @Size(max = 100) String fatherProfession,
			@Size(max = 200) String motherName, @Size(max = 20) String motherCnic, @Size(max = 30) String motherPhone,
			@Size(max = 254) String email, @Size(max = 500) String address) {
	}

	record EnrolRequest(@Size(max = 20) String grNo, @NotNull @Valid EnrolStudent student, Long familyId,
			@Valid EnrolFamily newFamily, @NotNull Long sectionId, LocalDate admissionDate,
			@Size(max = 20) String rollNo) {
	}

	record ContentRequest(@NotBlank String kind, Long classId, @Size(max = 20000) String body) {
	}

	private final AdmissionsService admissions;

	private final EnquiryStatuses statuses;

	private final AdmissionDocuments documents;

	AdmissionsController(AdmissionsService admissions, EnquiryStatuses statuses, AdmissionDocuments documents) {
		this.admissions = admissions;
		this.statuses = statuses;
		this.documents = documents;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/enquiries")
	PageResponse<EnquiryRow> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) Long statusId, @RequestParam(required = false) Long classId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return admissions.search(q, statusId, classId, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/enquiries/{id}")
	EnquiryView get(@PathVariable long id) {
		return admissions.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/enquiries")
	@ResponseStatus(HttpStatus.CREATED)
	EnquiryView create(@Valid @RequestBody EnquiryRequest body) {
		return admissions.create(body.details());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/enquiries/{id}")
	EnquiryView update(@PathVariable long id, @Valid @RequestBody EnquiryRequest body) {
		return admissions.update(id, body.details(), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/enquiries/{id}/status")
	EnquiryView status(@PathVariable long id, @Valid @RequestBody EnquiryStatusRequest body) {
		return admissions.changeStatus(id, body.statusId(), body.remark());
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/enquiries/{id}/test")
	EnquiryView test(@PathVariable long id, @Valid @RequestBody TestRequest body) {
		return admissions.setTest(id, new TestResult(body.testDate(), body.obtained(), body.total(), body.remark()));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/enquiries/{id}/remarks")
	List<Remark> remarks(@PathVariable long id) {
		return admissions.remarks(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/enquiries/{id}/remarks")
	@ResponseStatus(HttpStatus.CREATED)
	Remark addRemark(@PathVariable long id, @Valid @RequestBody RemarkRequest body) {
		return admissions.addRemark(id, body.text());
	}

	/** Possible duplicates while typing (FR 4.5). */
	@PreAuthorize(VIEW)
	@GetMapping("/duplicates")
	List<Match> duplicates(@RequestParam(required = false) String name,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
			@RequestParam(required = false) String phone, @RequestParam(required = false) String cnic,
			@RequestParam(required = false) Long excludeEnquiryId) {
		return admissions.duplicates(name, dob, phone, cnic, excludeEnquiryId);
	}

	@PreAuthorize("hasAuthority('" + Permissions.ADMISSIONS_ENROL + "')")
	@PostMapping("/enquiries/{id}/enrol")
	@ResponseStatus(HttpStatus.CREATED)
	StudentService.StudentView enrol(@PathVariable long id, @Valid @RequestBody EnrolRequest body) {
		var s = body.student();
		var f = body.newFamily();
		return admissions.enrol(id, new AdmissionsService.EnrolmentInput(body.grNo(),
				new StudentDetails(s.name(), s.gender(), s.dob(), s.placeOfBirth(), s.bFormNo(), s.religion(),
						s.nationality(), s.phone(), s.address(), s.previousSchool(), s.studyGroup(), null,
						s.photoFileId(), null),
				body.familyId(),
				f == null ? null : new FamilyDetails(f.fatherName(), f.fatherCnic(), f.fatherPhone(),
						f.fatherProfession(), f.motherName(), f.motherCnic(), f.motherPhone(), f.email(), f.address(),
						null, null, null, null),
				body.sectionId(), body.admissionDate(), body.rollNo()));
	}

	// -------------------------------------------------------------- statuses

	@PreAuthorize(VIEW)
	@GetMapping("/statuses")
	List<EnquiryStatuses.EnquiryStatus> statuses() {
		return statuses.list();
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/statuses")
	@ResponseStatus(HttpStatus.CREATED)
	EnquiryStatuses.EnquiryStatus createStatus(@Valid @RequestBody StatusDefinition body) {
		return statuses.create(body.name(), body.colour());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/statuses/{id}")
	EnquiryStatuses.EnquiryStatus updateStatus(@PathVariable long id, @Valid @RequestBody StatusDefinition body) {
		return statuses.update(id, body.name(), body.colour(), body.active() == null || body.active());
	}

	// --------------------------------------------------- terms and the form

	@PreAuthorize(VIEW)
	@GetMapping("/content")
	List<AdmissionDocuments.AdmissionContent> content() {
		return documents.list();
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/content")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void saveContent(@Valid @RequestBody ContentRequest body) {
		documents.save(body.kind(), body.classId(), body.body());
	}

	@PreAuthorize(VIEW)
	@GetMapping(value = "/form", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> form(@RequestParam(required = false) Long enquiryId,
			@RequestParam(required = false) Long classId) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("admission-form.pdf").build().toString())
			.body(documents.form(enquiryId, classId));
	}
}
