package com.selectcode.sms.iam.api;

import com.selectcode.sms.iam.app.UserAdminService;
import com.selectcode.sms.iam.app.UserAdminService.TemporaryPassword;
import com.selectcode.sms.iam.app.UserAdminService.UserInput;
import com.selectcode.sms.iam.app.UserAdminService.UserView;
import com.selectcode.sms.iam.domain.AppUser;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/iam/users")
class UserController {

	record CreateUserRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 200) String displayName,
			@Email @Size(max = 254) String email, @Size(max = 30) String phone, @NotEmpty List<Long> roleIds,
			com.selectcode.sms.shared.security.DataScope dataScope) {
	}

	record UpdateUserRequest(@NotBlank @Size(max = 200) String displayName, @Email @Size(max = 254) String email,
			@Size(max = 30) String phone, @NotEmpty List<Long> roleIds, com.selectcode.sms.shared.security.DataScope dataScope, long version) {
	}

	record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
	}

	private final UserAdminService users;

	UserController(UserAdminService users) {
		this.users = users;
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_VIEW + "')")
	@GetMapping
	PageResponse<UserView> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) AppUser.Status status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return users.search(q, status, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_VIEW + "')")
	@GetMapping("/{id}")
	UserView get(@PathVariable long id) {
		return users.get(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TemporaryPassword create(@Valid @RequestBody CreateUserRequest body) {
		return users.create(new UserInput(body.username(), body.displayName(), body.email(), body.phone(),
				body.roleIds(), body.dataScope()));
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PatchMapping("/{id}")
	UserView update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest body) {
		return users.update(id, new UserInput(null, body.displayName(), body.email(), body.phone(), body.roleIds(),
				body.dataScope()), body.version());
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/{id}/deactivate")
	UserView deactivate(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
		return users.deactivate(id, body.reason());
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/{id}/activate")
	UserView activate(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
		return users.activate(id, body.reason());
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/{id}/unlock")
	UserView unlock(@PathVariable long id) {
		return users.unlock(id);
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_USER_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/{id}/reset-password")
	TemporaryPassword resetPassword(@PathVariable long id) {
		return users.resetPassword(id);
	}
}
