package com.selectcode.sms.iam.api;

import com.selectcode.sms.iam.app.RoleService;
import com.selectcode.sms.iam.app.RoleService.RoleInput;
import com.selectcode.sms.iam.app.RoleService.RoleView;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/iam")
class RoleController {

	record CreateRoleRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name,
			@Size(max = 300) String description, @NotNull Set<String> permissions) {
	}

	record UpdateRoleRequest(@NotBlank @Size(max = 100) String name, @Size(max = 300) String description,
			@NotNull Set<String> permissions) {
	}

	private final RoleService roles;

	RoleController(RoleService roles) {
		this.roles = roles;
	}

	/** The permission catalogue, grouped for the permission matrix screen. */
	@PreAuthorize("hasAuthority('" + Permissions.IAM_ROLE_VIEW + "')")
	@GetMapping("/permissions")
	List<Permissions.Definition> permissions() {
		return Permissions.ALL;
	}

	@PreAuthorize("hasAnyAuthority('" + Permissions.IAM_ROLE_VIEW + "', '" + Permissions.IAM_USER_VIEW + "')")
	@GetMapping("/roles")
	List<RoleView> list() {
		return roles.list();
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_ROLE_MANAGE + "')")
	@RequiresStepUp
	@PostMapping("/roles")
	@ResponseStatus(HttpStatus.CREATED)
	RoleView create(@Valid @RequestBody CreateRoleRequest body) {
		return roles.create(new RoleInput(body.code(), body.name(), body.description(), body.permissions()));
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_ROLE_MANAGE + "')")
	@RequiresStepUp
	@PutMapping("/roles/{id}")
	RoleView update(@PathVariable long id, @Valid @RequestBody UpdateRoleRequest body) {
		return roles.update(id, new RoleInput(null, body.name(), body.description(), body.permissions()));
	}

	@PreAuthorize("hasAuthority('" + Permissions.IAM_ROLE_MANAGE + "')")
	@RequiresStepUp
	@DeleteMapping("/roles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long id) {
		roles.delete(id);
	}
}
