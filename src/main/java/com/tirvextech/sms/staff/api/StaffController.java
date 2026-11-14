package com.selectcode.sms.staff.api;

import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.shared.pdf.CertificateRegister;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.staff.StaffService;
import com.selectcode.sms.staff.StaffService.StaffDocumentView;
import com.selectcode.sms.staff.StaffService.SalaryEntry;
import com.selectcode.sms.staff.StaffService.SalaryInput;
import com.selectcode.sms.staff.StaffService.StaffRow;
import com.selectcode.sms.staff.StaffService.StaffView;
import com.selectcode.sms.staff.app.ExperienceCertificate;
import com.selectcode.sms.staff.domain.Staff.StaffDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
class StaffController {

	private static final String VIEW = "hasAuthority('" + Permissions.STAFF_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.STAFF_MANAGE + "')";

	record StaffRequest(@NotBlank String category, @NotBlank @Size(max = 200) String name,
			@Size(max = 200) String fatherOrHusbandName, String gender, LocalDate dob, @Size(max = 20) String cnic,
			@Size(max = 30) String phone, @Size(max = 254) String email, @Size(max = 500) String address,
			@Size(max = 200) String qualification, @Size(max = 200) String experience,
			@NotBlank @Size(max = 100) String designation, String employmentType, @NotNull LocalDate joiningDate,
			Long photoFileId, @Size(max = 1000) String notes, Long version) {

		StaffDetails details() {
			return new StaffDetails(category, name, fatherOrHusbandName, gender, dob, cnic, phone, email, address,
					qualification, experience, designation, employmentType, joiningDate, photoFileId, notes);
		}
	}

	record LeaveRequest(@NotNull LocalDate leavingDate, @NotBlank @Size(max = 500) String reason) {
	}

	record StaffLoginRequest(@NotBlank @Size(max = 60) String username, @NotEmpty List<Long> roleIds, DataScope dataScope) {
	}

	record StaffDocumentRequest(@NotNull Long fileId, @Size(max = 40) String docType, @Size(max = 200) String title) {
	}

	record SalaryRequest(@NotNull LocalDate effectiveFrom, @NotBlank String basicSalary, String perLectureRate,
			@Size(max = 100) String bankName, @Size(max = 60) String bankAccount, @NotBlank @Size(max = 300) String reason) {
	}

	record ExperienceCertificateRequest(@Size(max = 1000) String remarks) {
	}

	private final StaffService staff;

	private final ExperienceCertificate experience;

	private final CertificateRegister certificates;

	StaffController(StaffService staff, ExperienceCertificate experience, CertificateRegister certificates) {
		this.staff = staff;
		this.experience = experience;
		this.certificates = certificates;
	}

	@PreAuthorize(VIEW)
	@GetMapping
	PageResponse<StaffRow> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) String category, @RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return staff.search(q, category, status, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}")
	StaffView get(@PathVariable long id) {
		return staff.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StaffView create(@Valid @RequestBody StaffRequest body) {
		return staff.create(body.details());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/{id}")
	StaffView update(@PathVariable long id, @Valid @RequestBody StaffRequest body) {
		return staff.update(id, body.details(), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/{id}/leave")
	StaffView leave(@PathVariable long id, @Valid @RequestBody LeaveRequest body) {
		return staff.leave(id, body.leavingDate(), body.reason());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/{id}/rejoin")
	StaffView rejoin(@PathVariable long id) {
		return staff.rejoin(id);
	}

	/** Creating a login is user management: needs both permissions and a step-up code. */
	@PreAuthorize("hasAuthority('" + Permissions.STAFF_MANAGE + "') and hasAuthority('" + Permissions.IAM_USER_MANAGE
			+ "')")
	@RequiresStepUp
	@PostMapping("/{id}/login")
	@ResponseStatus(HttpStatus.CREATED)
	SchoolUserAdministration.NewLogin createLogin(@PathVariable long id, @Valid @RequestBody StaffLoginRequest body) {
		return staff.createLogin(id, body.username(), body.roleIds(), body.dataScope());
	}

	@PreAuthorize(MANAGE)
	@GetMapping("/{id}/documents")
	List<StaffDocumentView> documents(@PathVariable long id) {
		return staff.documents(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/{id}/documents")
	@ResponseStatus(HttpStatus.CREATED)
	StaffDocumentView addDocument(@PathVariable long id, @Valid @RequestBody StaffDocumentRequest body) {
		return staff.addDocument(id, body.docType(), body.title(), body.fileId());
	}

	@PreAuthorize(MANAGE)
	@DeleteMapping("/{id}/documents/{documentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeDocument(@PathVariable long id, @PathVariable long documentId) {
		staff.removeDocument(id, documentId);
	}

	@PreAuthorize("hasAuthority('" + Permissions.STAFF_SALARY_VIEW + "')")
	@GetMapping("/{id}/salary")
	List<SalaryEntry> salary(@PathVariable long id) {
		return staff.salaryHistory(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.STAFF_SALARY_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/{id}/salary")
	@ResponseStatus(HttpStatus.CREATED)
	SalaryEntry addSalary(@PathVariable long id, @Valid @RequestBody SalaryRequest body) {
		return staff.addSalary(id, new SalaryInput(body.effectiveFrom(), body.basicSalary(), body.perLectureRate(),
				body.bankName(), body.bankAccount(), body.reason()));
	}

	@PreAuthorize(MANAGE)
	@GetMapping("/{id}/certificates")
	List<CertificateRegister.CertificateEntry> certificates(@PathVariable long id) {
		return certificates.forStaff(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping(value = "/{id}/certificates/experience", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> experienceCertificate(@PathVariable long id,
			@Valid @RequestBody(required = false) ExperienceCertificateRequest body) {
		byte[] pdf = experience.issue(id, body == null ? null : body.remarks());
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("experience-certificate.pdf").build().toString())
			.body(pdf);
	}
}
