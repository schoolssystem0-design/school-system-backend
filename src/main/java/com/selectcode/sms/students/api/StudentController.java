package com.selectcode.sms.students.api;

import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.shared.pdf.CertificateRegister;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.students.StudentService;
import com.selectcode.sms.students.StudentService.Admission;
import com.selectcode.sms.students.StudentService.StudentBulkValue;
import com.selectcode.sms.students.StudentService.RegisterRow;
import com.selectcode.sms.students.StudentService.Strength;
import com.selectcode.sms.students.StudentService.StudentRow;
import com.selectcode.sms.students.StudentService.StudentView;
import com.selectcode.sms.students.app.PromotionService;
import com.selectcode.sms.students.app.StudentPrintService;
import com.selectcode.sms.students.app.StudentRecordsService;
import com.selectcode.sms.students.infra.Enrolments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/students")
class StudentController {

	private static final String VIEW = "hasAuthority('" + Permissions.STUDENTS_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.STUDENTS_MANAGE + "')";

	private static final String MOVE = "hasAuthority('" + Permissions.STUDENTS_MOVE + "')";

	private static final String PRINT = "hasAuthority('" + Permissions.STUDENTS_PRINT + "')";

	record StudentInput(@NotBlank @Size(max = 200) String name, @NotBlank String gender, LocalDate dob,
			@Size(max = 100) String placeOfBirth, @Size(max = 20) String bFormNo, @Size(max = 40) String religion,
			@Size(max = 40) String nationality, @Size(max = 30) String phone, @Size(max = 500) String address,
			@Size(max = 200) String previousSchool, @Size(max = 40) String studyGroup, @Size(max = 30) String seatNo,
			Long photoFileId, @Size(max = 1000) String notes) {

		com.selectcode.sms.students.StudentDetails toDetails() {
			return new com.selectcode.sms.students.StudentDetails(name, gender, dob, placeOfBirth, bFormNo, religion, nationality, phone, address,
					previousSchool, studyGroup, seatNo, photoFileId, notes);
		}
	}

	record NewFamily(@Size(max = 200) String fatherName, @Size(max = 20) String fatherCnic,
			@Size(max = 30) String fatherPhone, @Size(max = 100) String fatherProfession,
			@Size(max = 200) String motherName, @Size(max = 20) String motherCnic, @Size(max = 30) String motherPhone,
			@Size(max = 254) String email, @Size(max = 500) String address, String preferredChannel) {

		FamilyDetails toDetails() {
			return new FamilyDetails(fatherName, fatherCnic, fatherPhone, fatherProfession, motherName, motherCnic,
					motherPhone, email, address, null, null, preferredChannel, null);
		}
	}

	record AdmitRequest(@Size(max = 20) String grNo, @NotNull @Valid StudentInput student, Long familyId,
			@Valid NewFamily newFamily, @NotNull Long sectionId, LocalDate admissionDate, @Size(max = 20) String rollNo,
			Long enquiryId) {
	}

	record UpdateRequest(@Size(max = 20) String grNo, @NotNull @Valid StudentInput student, Long version) {
	}

	record StudentFamilyRequest(@NotNull Long familyId) {
	}

	record StudentBulkRequest(@NotBlank String field, @NotNull List<@Valid StudentBulkItem> values) {
	}

	record StudentBulkItem(@NotNull Long studentId, @Size(max = 200) String value) {
	}

	record MoveRequest(@NotNull Long toSectionId, LocalDate effectiveDate, @Size(max = 300) String reason) {
	}

	record WithdrawRequest(LocalDate leavingDate, @NotBlank @Size(max = 500) String reason) {
	}

	record ReadmitRequest(@NotNull Long sectionId, LocalDate date, @Size(max = 300) String reason) {
	}

	record StudentDocumentRequest(@NotNull Long fileId, String docType, @Size(max = 200) String title) {
	}

	record ContactRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 60) String relationship,
			@Size(max = 30) String phone, @Size(max = 20) String cnic, boolean canCollect, boolean receivesAttendance,
			boolean receivesFees, boolean receivesResults) {
	}

	record GroupRequest(@NotBlank String kind, @NotBlank @Size(max = 100) String name) {
	}

	record ActivityRequest(@NotBlank String kind, Long groupId, @Size(max = 60) String role,
			@Size(max = 200) String title, @Size(max = 1000) String description, LocalDate achievedOn) {
	}

	record StudentCertificateRequest(@Size(max = 100) String conduct, @Size(max = 300) String reason,
			@Size(max = 1000) String remarks, @Size(max = 100) String examination, @Size(max = 60) String session,
			@Size(max = 30) String seatNo, @Size(max = 100) String result) {
	}

	record IdCardRequest(List<Long> studentIds, Long sectionId) {
	}

	record PromotionRequest(@NotNull Long fromYearId, @NotNull Long toYearId, @NotNull List<@Valid PromotionItem> items) {
	}

	record PromotionItem(@NotNull Long studentId, @NotNull PromotionService.Decision decision, Long toSectionId,
			@Size(max = 300) String reason) {
	}

	private final StudentService students;

	private final StudentRecordsService records;

	private final PromotionService promotions;

	private final StudentPrintService printing;

	private final CertificateRegister certificates;

	private final YearContext yearContext;

	StudentController(StudentService students, StudentRecordsService records, PromotionService promotions,
			StudentPrintService printing, CertificateRegister certificates, YearContext yearContext) {
		this.students = students;
		this.records = records;
		this.promotions = promotions;
		this.printing = printing;
		this.certificates = certificates;
		this.yearContext = yearContext;
	}

	// ----------------------------------------------------------------- lists

	@PreAuthorize(VIEW)
	@GetMapping
	PageResponse<StudentRow> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) Long classId, @RequestParam(required = false) Long sectionId,
			@RequestParam(required = false) Long familyId, @RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return students.search(q, classId, sectionId, familyId, status, PageResponse.page(page),
				PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/strength")
	List<Strength> strength() {
		return students.strength();
	}

	@PreAuthorize(VIEW)
	@GetMapping("/register")
	List<RegisterRow> register(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return students.register(from, to);
	}

	@PreAuthorize("hasAnyAuthority('" + Permissions.ADMISSIONS_ENROL + "', '" + Permissions.STUDENTS_MANAGE + "')")
	@GetMapping("/suggest-gr")
	Map<String, String> suggestGr() {
		return Map.of("grNo", students.suggestGrNo());
	}

	// ------------------------------------------------------------- admission

	@PreAuthorize("hasAuthority('" + Permissions.ADMISSIONS_ENROL + "')")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StudentView admit(@Valid @RequestBody AdmitRequest body) {
		return students.admit(new Admission(body.grNo(), body.student().toDetails(), body.familyId(),
				body.newFamily() == null ? null : body.newFamily().toDetails(), body.sectionId(), body.admissionDate(),
				body.rollNo(), body.enquiryId()));
	}

	// --------------------------------------------------------------- profile

	@PreAuthorize(VIEW)
	@GetMapping("/{id}")
	StudentView get(@PathVariable long id) {
		return students.get(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}/enrolments")
	List<Enrolments.Enrolment> enrolments(@PathVariable long id) {
		return students.enrolmentHistory(id);
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/{id}")
	StudentView update(@PathVariable long id, @Valid @RequestBody UpdateRequest body) {
		return students.update(id, body.grNo(), body.student().toDetails(), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize("hasAuthority('" + Permissions.STUDENTS_MANAGE + "') and hasAuthority('" + Permissions.FAMILIES_MANAGE
			+ "')")
	@PutMapping("/{id}/family")
	StudentView changeFamily(@PathVariable long id, @Valid @RequestBody StudentFamilyRequest body) {
		return students.changeFamily(id, body.familyId());
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/bulk")
	Map<String, Integer> bulk(@Valid @RequestBody StudentBulkRequest body) {
		return Map.of("updated", students.bulkUpdate(body.field(),
				body.values().stream().map(v -> new StudentBulkValue(v.studentId(), v.value())).toList()));
	}

	// ------------------------------------------------------- class changes

	@PreAuthorize(MOVE)
	@PostMapping("/{id}/move")
	StudentView move(@PathVariable long id, @Valid @RequestBody MoveRequest body) {
		return students.move(id, body.toSectionId(), body.effectiveDate(), body.reason());
	}

	@PreAuthorize(MOVE)
	@PostMapping("/{id}/withdraw")
	StudentView withdraw(@PathVariable long id, @Valid @RequestBody WithdrawRequest body) {
		return students.withdraw(id, body.leavingDate(), body.reason());
	}

	@PreAuthorize(MOVE)
	@PostMapping("/{id}/readmit")
	StudentView readmit(@PathVariable long id, @Valid @RequestBody ReadmitRequest body) {
		return students.readmit(id, body.sectionId(), body.date(), body.reason());
	}

	@PreAuthorize(MOVE)
	@GetMapping("/promotion/preview")
	PromotionService.Preview promotionPreview(@RequestParam long fromYearId, @RequestParam long toYearId,
			@RequestParam long classId) {
		return promotions.preview(fromYearId, toYearId, classId);
	}

	@PreAuthorize(MOVE)
	@PostMapping("/promotion")
	PromotionService.PromotionResult promote(@Valid @RequestBody PromotionRequest body) {
		return promotions.apply(body.fromYearId(), body.toYearId(), body.items()
			.stream()
			.map(i -> new PromotionService.Item(i.studentId(), i.decision(), i.toSectionId(), i.reason()))
			.toList());
	}

	// --------------------------------------------------------------- records

	@PreAuthorize(VIEW)
	@GetMapping("/{id}/documents")
	List<StudentRecordsService.StudentDocumentView> documents(@PathVariable long id) {
		return records.documents(id);
	}

	@PreAuthorize("hasAnyAuthority('" + Permissions.STUDENTS_MANAGE + "', '" + Permissions.ADMISSIONS_ENROL + "')")
	@PostMapping("/{id}/documents")
	@ResponseStatus(HttpStatus.CREATED)
	StudentRecordsService.StudentDocumentView addDocument(@PathVariable long id, @Valid @RequestBody StudentDocumentRequest body) {
		return records.addDocument(id, body.docType(), body.title(), body.fileId());
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/{id}/documents/{documentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeDocument(@PathVariable long id, @PathVariable long documentId) {
		records.removeDocument(id, documentId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}/contacts")
	List<StudentRecordsService.ContactView> contacts(@PathVariable long id) {
		return records.contacts(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.FAMILIES_MANAGE + "')")
	@PostMapping("/{id}/contacts")
	@ResponseStatus(HttpStatus.CREATED)
	StudentRecordsService.ContactView addContact(@PathVariable long id, @Valid @RequestBody ContactRequest body) {
		return records.addContact(id, new StudentRecordsService.ContactInput(body.name(), body.relationship(),
				body.phone(), body.cnic(), body.canCollect(), body.receivesAttendance(), body.receivesFees(),
				body.receivesResults()));
	}

	@PreAuthorize("hasAuthority('" + Permissions.FAMILIES_MANAGE + "')")
	@DeleteMapping("/{id}/contacts/{contactId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeContact(@PathVariable long id, @PathVariable long contactId) {
		records.removeContact(id, contactId);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/activity-groups")
	List<StudentRecordsService.GroupView> groups() {
		return records.groups();
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/activity-groups")
	@ResponseStatus(HttpStatus.CREATED)
	StudentRecordsService.GroupView createGroup(@Valid @RequestBody GroupRequest body) {
		return records.createGroup(body.kind(), body.name());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}/activities")
	List<StudentRecordsService.ActivityView> activities(@PathVariable long id) {
		return records.activities(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/{id}/activities")
	@ResponseStatus(HttpStatus.CREATED)
	StudentRecordsService.ActivityView addActivity(@PathVariable long id, @Valid @RequestBody ActivityRequest body) {
		return records.addActivity(id, new StudentRecordsService.ActivityInput(body.kind(), body.groupId(),
				body.role(), body.title(), body.description(), body.achievedOn()));
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/{id}/activities/{activityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeActivity(@PathVariable long id, @PathVariable long activityId) {
		records.removeActivity(id, activityId);
	}

	// -------------------------------------------------------------- printing

	@PreAuthorize(VIEW)
	@GetMapping("/{id}/certificates")
	List<CertificateRegister.CertificateEntry> certificates(@PathVariable long id) {
		students.get(id);
		return certificates.forStudent(id);
	}

	@PreAuthorize(PRINT)
	@PostMapping(value = "/{id}/certificates/{type}", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> certificate(@PathVariable long id, @PathVariable CertificateRegister.CertificateType type,
			@Valid @RequestBody(required = false) StudentCertificateRequest body) {
		var in = body == null ? new StudentCertificateRequest(null, null, null, null, null, null, null) : body;
		return pdf(printing.certificate(id, type, new StudentPrintService.CertificateInput(in.conduct(), in.reason(),
				in.remarks(), in.examination(), in.session(), in.seatNo(), in.result())),
				type.name().toLowerCase() + "-certificate.pdf");
	}

	@PreAuthorize(PRINT)
	@GetMapping(value = "/{id}/bio-data", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> bioData(@PathVariable long id) {
		return pdf(printing.bioData(id), "bio-data.pdf");
	}

	@PreAuthorize(PRINT)
	@PostMapping(value = "/id-cards", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> idCards(@RequestBody IdCardRequest body) {
		List<Long> ids;
		if (body.studentIds() != null && !body.studentIds().isEmpty()) {
			ids = body.studentIds();
		}
		else if (body.sectionId() != null) {
			ids = students.idsInSection(body.sectionId(), yearContext.requireSelected());
		}
		else {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose students or a section");
		}
		return pdf(printing.idCards(ids), "id-cards.pdf");
	}

	private static ResponseEntity<byte[]> pdf(byte[] content, String filename) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(content);
	}
}
