package com.selectcode.sms.dashboard.api;

import com.selectcode.sms.dashboard.app.DashboardService;
import com.selectcode.sms.dashboard.app.DashboardService.Dashboard;
import com.selectcode.sms.dashboard.app.DashboardService.Favourite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard (FR 3). Every signed-in school user has one; each panel is decided by that user's own permissions and
 * data scope in the service, and panels not allowed are absent from the response.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

	private static final String SIGNED_IN = "isAuthenticated()";

	record FavouritesRequest(@NotNull List<Favourite> pages) {
	}

	private final DashboardService dashboard;

	DashboardController(DashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@PreAuthorize(SIGNED_IN)
	@GetMapping
	Dashboard dashboard(@RequestParam(defaultValue = "false") boolean reveal) {
		return dashboard.dashboard(reveal);
	}

	@PreAuthorize(SIGNED_IN)
	@PutMapping("/favourites")
	List<Favourite> favourites(@Valid @RequestBody FavouritesRequest body) {
		return dashboard.saveFavourites(body.pages());
	}
}
