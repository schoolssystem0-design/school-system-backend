package com.selectcode.sms.platform.api;

import com.selectcode.sms.platform.app.TrialService;
import com.selectcode.sms.platform.app.TrialService.TrialRequest;
import com.selectcode.sms.platform.app.TrialService.TrialStarted;
import com.selectcode.sms.shared.security.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Free trial sign-up from the public site (no account needed). One trial per internet connection and per email. */
@RestController
class PublicTrialController {

	record TrialSignupRequest(@NotBlank @Size(max = 200) String schoolName, @NotBlank @Size(max = 20) String schoolCode,
			@NotBlank @Size(max = 200) String adminName, @NotBlank @Size(max = 60) String username,
			@NotBlank @Size(max = 254) String email, @Size(max = 200) String website) {
	}

	record TrialInfo(int days) {
	}

	private final TrialService trials;

	PublicTrialController(TrialService trials) {
		this.trials = trials;
	}

	@PublicEndpoint
	@GetMapping("/api/v1/public/trial")
	TrialInfo info() {
		return new TrialInfo(trials.days());
	}

	@PublicEndpoint
	@PostMapping("/api/v1/public/trial")
	@ResponseStatus(HttpStatus.CREATED)
	TrialStarted start(@Valid @RequestBody TrialSignupRequest body, HttpServletRequest request) {
		if (body.website() != null && !body.website().isBlank()) {
			// A hidden field people never see: only bots fill it in
			throw com.selectcode.sms.shared.web.ApiException.businessRule("TRIAL_REFUSED", "The trial could not be started");
		}
		return trials.start(new TrialRequest(body.schoolName(), body.schoolCode(), body.adminName(), body.username(),
				body.email()), request.getRemoteAddr());
	}
}
