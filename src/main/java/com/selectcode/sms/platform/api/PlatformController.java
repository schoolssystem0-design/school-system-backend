package com.selectcode.sms.platform.api;

import com.selectcode.sms.iam.SchoolUserAdministration.SetupLink;
import com.selectcode.sms.platform.PlatformPrincipal;
import com.selectcode.sms.platform.app.ActiveSchoolService;
import com.selectcode.sms.platform.app.PlatformAuthService;
import com.selectcode.sms.platform.app.PlatformOverviewService;
import com.selectcode.sms.platform.app.SchoolManagementService;
import com.selectcode.sms.platform.app.SchoolManagementService.NewSchool;
import com.selectcode.sms.platform.app.SchoolManagementService.SchoolCreated;
import com.selectcode.sms.platform.app.SchoolManagementService.SchoolView;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.PublicEndpoint;
import com.selectcode.sms.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
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

/**
 * Super admin (system owner) API. Everything except sign-in requires ROLE_SUPER_ADMIN (SecurityConfig and
 * {@code @PreAuthorize}).
 */
@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {

	private static final String SUPER_ADMIN = "hasAuthority('" + CurrentActor.SUPER_ADMIN_AUTHORITY + "')";

	record PlatformLoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 200) String password,
			@Size(max = 10) String totpCode) {
	}

	/** Either signed in ({@code status=SIGNED_IN}) or the authenticator must be set up ({@code ENROL_TOTP}). */
	record PlatformLoginResponse(String status, String totpSecret, String otpauthUri, PlatformMe me) {
	}

	record TotpEnrolRequest(@NotBlank @Size(max = 10) String code) {
	}

	record PlatformMe(long id, String email, String name, Long activeSchoolId, String activeSchoolCode,
			String activeSchoolName) {

		static PlatformMe of(PlatformPrincipal p) {
			return new PlatformMe(p.platformUserId(), p.email(), p.name(), p.activeSchoolId(), p.activeSchoolCode(),
					p.activeSchoolName());
		}
	}

	record CreateSchoolRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 200) String name,
			@NotBlank @Size(max = 200) String adminName, @NotBlank @Size(max = 60) String adminUsername,
			@NotBlank @Email @Size(max = 254) String adminEmail) {
	}

	record RenameRequest(@NotBlank @Size(max = 200) String name) {
	}

	record SuspendRequest(@NotBlank @Size(max = 500) String reason) {
	}

	record DomainRequest(@NotBlank @Size(max = 253) String host, Boolean primary) {
	}

	private final PlatformAuthService auth;

	private final SchoolManagementService schools;

	private final PlatformOverviewService overview;

	private final ActiveSchoolService activeSchool;

	private final SecurityContextRepository securityContextRepository;

	PlatformController(PlatformAuthService auth, SchoolManagementService schools, PlatformOverviewService overview,
			ActiveSchoolService activeSchool, SecurityContextRepository securityContextRepository) {
		this.auth = auth;
		this.schools = schools;
		this.overview = overview;
		this.activeSchool = activeSchool;
		this.securityContextRepository = securityContextRepository;
	}

	// ------------------------------------------------------------ sign-in

	@PublicEndpoint
	@PostMapping("/auth/login")
	PlatformLoginResponse login(@Valid @RequestBody PlatformLoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		var outcome = auth.signIn(body.email(), body.password(), body.totpCode(), request.getRemoteAddr(),
				request.getHeader(HttpHeaders.USER_AGENT), request.getSession(true));
		return switch (outcome) {
			case PlatformAuthService.Outcome.SignedIn signedIn -> {
				establish(signedIn.principal(), request, response);
				yield new PlatformLoginResponse("SIGNED_IN", null, null, PlatformMe.of(signedIn.principal()));
			}
			case PlatformAuthService.Outcome.EnrolmentRequired enrol ->
				new PlatformLoginResponse("ENROL_TOTP", enrol.secret(), enrol.otpauthUri(), null);
		};
	}

	@PublicEndpoint
	@PostMapping("/auth/totp/enroll")
	PlatformLoginResponse enrol(@Valid @RequestBody TotpEnrolRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		PlatformPrincipal principal = auth.completeEnrolment(body.code(), request.getRemoteAddr(),
				request.getHeader(HttpHeaders.USER_AGENT), request.getSession(true));
		establish(principal, request, response);
		return new PlatformLoginResponse("SIGNED_IN", null, null, PlatformMe.of(principal));
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/auth/me")
	PlatformMe me() {
		return PlatformMe.of((PlatformPrincipal) CurrentActor.require());
	}

	// ------------------------------------------------------------ overview

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/overview")
	PlatformOverviewService.Overview overview() {
		return overview.overview();
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/audit")
	PageResponse<PlatformOverviewService.PlatformAuditRow> audit(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return overview.audit(PageResponse.page(page), PageResponse.size(size));
	}

	// ------------------------------------------------------------ schools

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/schools")
	List<SchoolView> schools() {
		return schools.list();
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/schools")
	@ResponseStatus(HttpStatus.CREATED)
	SchoolCreated create(@Valid @RequestBody CreateSchoolRequest body) {
		return schools.create(new NewSchool(body.code(), body.name(), body.adminName(), body.adminUsername(),
				body.adminEmail()));
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/schools/{id}")
	SchoolView school(@PathVariable long id) {
		return schools.get(id);
	}

	@PreAuthorize(SUPER_ADMIN)
	@PatchMapping("/schools/{id}")
	SchoolView rename(@PathVariable long id, @Valid @RequestBody RenameRequest body) {
		return schools.rename(id, body.name());
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/schools/{id}/suspend")
	SchoolView suspend(@PathVariable long id, @Valid @RequestBody SuspendRequest body) {
		return schools.suspend(id, body.reason());
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/schools/{id}/reactivate")
	SchoolView reactivate(@PathVariable long id) {
		return schools.reactivate(id);
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/schools/{id}/domains")
	SchoolView addDomain(@PathVariable long id, @Valid @RequestBody DomainRequest body) {
		return schools.addDomain(id, body.host(), Boolean.TRUE.equals(body.primary()));
	}

	@PreAuthorize(SUPER_ADMIN)
	@DeleteMapping("/schools/{id}/domains/{domainId}")
	SchoolView removeDomain(@PathVariable long id, @PathVariable long domainId) {
		return schools.removeDomain(id, domainId);
	}

	@PreAuthorize(SUPER_ADMIN)
	@GetMapping("/schools/{id}/users")
	List<Map<String, Object>> users(@PathVariable long id) {
		schools.get(id);
		return overview.schoolUsers(id);
	}

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/schools/{id}/users/{userId}/setup-link")
	SetupLink setupLink(@PathVariable long id, @PathVariable long userId) {
		return schools.issueSetupLink(id, userId);
	}

	// ------------------------------------------------------- active school

	@PreAuthorize(SUPER_ADMIN)
	@PostMapping("/active-school/{schoolId}")
	PlatformMe openSchool(@PathVariable long schoolId, HttpServletRequest request, HttpServletResponse response) {
		return PlatformMe.of(activeSchool.open(schoolId, request, response));
	}

	@PreAuthorize(SUPER_ADMIN)
	@DeleteMapping("/active-school")
	PlatformMe leaveSchool(HttpServletRequest request, HttpServletResponse response) {
		return PlatformMe.of(activeSchool.leave(request, response));
	}

	private void establish(PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
	}
}
