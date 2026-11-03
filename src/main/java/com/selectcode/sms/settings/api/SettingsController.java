package com.selectcode.sms.settings.api;

import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

	record ChangeRequest(@NotNull Object value, @NotBlank @Size(max = 500) String reason) {
	}

	private final SettingsService settings;

	SettingsController(SettingsService settings) {
		this.settings = settings;
	}

	@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_VIEW + "')")
	@GetMapping
	List<SettingsService.SettingView> list() {
		return settings.list();
	}

	@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_VIEW + "')")
	@GetMapping("/{key}/history")
	List<SettingsService.HistoryEntry> history(@PathVariable String key) {
		return settings.history(key);
	}

	@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_EDIT + "')")
	@RequiresStepUp
	@PutMapping("/{key}")
	SettingsService.SettingView change(@PathVariable String key, @Valid @RequestBody ChangeRequest body) {
		return settings.change(key, body.value(), body.reason());
	}
}
