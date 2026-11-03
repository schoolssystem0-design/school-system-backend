package com.selectcode.sms.families.api;

import com.selectcode.sms.families.FamilyService;
import com.selectcode.sms.families.FamilyService.FamilyBulkValue;
import com.selectcode.sms.families.FamilyService.DataIssue;
import com.selectcode.sms.families.FamilyService.FamilyRow;
import com.selectcode.sms.families.FamilyService.FamilyView;
import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/families")
class FamilyController {

	private static final String VIEW = "hasAuthority('" + Permissions.FAMILIES_VIEW + "')";

	private static final String MANAGE = "hasAuthority('" + Permissions.FAMILIES_MANAGE + "')";

	record FamilyRequest(@Size(max = 200) String fatherName, @Size(max = 20) String fatherCnic,
			@Size(max = 30) String fatherPhone, @Size(max = 100) String fatherProfession,
			@Size(max = 200) String motherName, @Size(max = 20) String motherCnic, @Size(max = 30) String motherPhone,
			@Size(max = 254) String email, @Size(max = 500) String address, @Size(max = 200) String emergencyName,
			@Size(max = 30) String emergencyPhone, String preferredChannel, @Size(max = 1000) String remarks,
			Long version) {

		FamilyDetails details() {
			return new FamilyDetails(fatherName, fatherCnic, fatherPhone, fatherProfession, motherName, motherCnic,
					motherPhone, email, address, emergencyName, emergencyPhone, preferredChannel, remarks);
		}
	}

	record FamilyBulkRequest(@NotBlank String field, @NotNull List<@Valid FamilyBulkItem> values) {
	}

	record FamilyBulkItem(@NotNull Long familyId, @Size(max = 500) String value) {
	}

	private final FamilyService families;

	FamilyController(FamilyService families) {
		this.families = families;
	}

	@PreAuthorize(VIEW)
	@GetMapping
	PageResponse<FamilyRow> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return families.search(q, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/{id}")
	FamilyView get(@PathVariable long id) {
		return families.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	FamilyView create(@Valid @RequestBody FamilyRequest body) {
		return families.create(body.details());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/{id}")
	FamilyView update(@PathVariable long id, @Valid @RequestBody FamilyRequest body) {
		return families.update(id, body.details(), body.version() == null ? -1 : body.version());
	}

	@PreAuthorize(MANAGE)
	@PutMapping("/bulk")
	Map<String, Integer> bulk(@Valid @RequestBody FamilyBulkRequest body) {
		return Map.of("updated", families.bulkUpdate(body.field(),
				body.values().stream().map(v -> new FamilyBulkValue(v.familyId(), v.value())).toList()));
	}

	/** Data-quality report (FR 8.5). */
	@PreAuthorize(MANAGE)
	@GetMapping("/data-issues")
	List<DataIssue> dataIssues() {
		return families.dataIssues();
	}
}
