package com.selectcode.sms.iam.api;

import com.selectcode.sms.iam.SchoolUserPrincipal;
import com.selectcode.sms.iam.app.AccountService;
import com.selectcode.sms.iam.app.SignInService;
import com.selectcode.sms.iam.app.SignInService.SignInAttempt;
import com.selectcode.sms.iam.app.StepUpService;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.AnyAuthenticated;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.security.PublicEndpoint;
import com.selectcode.sms.shared.security.RequestPrincipalFilter;
import com.selectcode.sms.shared.security.StepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in and own-account endpoints. Sign-out is {@code POST /api/v1/auth/logout} (Spring Security). */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	record LoginRequest(@NotBlank @Size(max = 20) String schoolCode, @NotBlank @Size(max = 60) String username,
			@NotBlank @Size(max = 200) String password) {
	}

	record SchoolInfo(long id, String code, String name) {
	}

	/** Who is signed in and what they may do. Used by the app to build menus (the API still checks every call). */
	record MeResponse(String actorType, long userId, String displayName, String loginName, SchoolInfo school,
			boolean superAdmin, List<String> permissions, boolean mustChangePassword, Instant stepUpUntil) {
	}

	record CsrfResponse(String headerName, String token) {
	}

	record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
			@NotBlank @Size(max = 200) String newPassword) {
	}

	record TokenPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword) {
	}

	record ResetRequest(@Size(max = 20) String schoolCode, @Size(max = 60) String username) {
	}

	record StepUpVerifyRequest(@NotBlank @Size(max = 10) String code) {
	}

	record StepUpVerified(Instant stepUpUntil) {
	}

	private final SignInService signInService;

	private final AccountService accounts;

	private final StepUpService stepUp;

	private final SecurityContextRepository securityContextRepository;

	AuthController(SignInService signInService, AccountService accounts, StepUpService stepUp,
			SecurityContextRepository securityContextRepository) {
		this.signInService = signInService;
		this.accounts = accounts;
		this.stepUp = stepUp;
		this.securityContextRepository = securityContextRepository;
	}

	/** Issues the CSRF cookie; call once before the first state-changing request (including login). */
	@PublicEndpoint
	@GetMapping("/csrf")
	CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@PublicEndpoint
	@PostMapping("/login")
	MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		SchoolUserPrincipal principal = signInService.signIn(new SignInAttempt(body.schoolCode(), body.username(),
				body.password(), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
		// Session fixation protection: never reuse a pre-login session ID.
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		// Permissions are loaded on the next request; the app calls /me right after sign-in.
		return new MeResponse("USER", principal.userId(), principal.displayName(), principal.username(),
				new SchoolInfo(principal.schoolId(), principal.schoolCode(), principal.schoolName()), false, List.of(),
				false, null);
	}

	@AnyAuthenticated
	@GetMapping("/me")
	MeResponse me(HttpServletRequest request) {
		ActorPrincipal actor = CurrentActor.require();
		var auth = SecurityContextHolder.getContext().getAuthentication();
		List<String> permissions = auth.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(Permissions::exists)
			.sorted()
			.toList();
		SchoolInfo school = actor.tenantSchoolId() == null ? null
				: new SchoolInfo(actor.tenantSchoolId(), actor.schoolCode(), actor.schoolName());
		return new MeResponse(actor.actorType().name(), actor.actorId(), actor.displayName(), actor.loginName(), school,
				CurrentActor.isSuperAdmin(), permissions,
				Boolean.TRUE.equals(request.getAttribute(RequestPrincipalFilter.MUST_CHANGE_PASSWORD_ATTRIBUTE)),
				StepUp.verifiedUntil(request.getSession(false)));
	}

	@AnyAuthenticated
	@PostMapping("/change-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpSession session) {
		accounts.changePassword(body.currentPassword(), body.newPassword(), session.getId());
	}

	/** Completes an account setup link (new user) and sets the first password. */
	@PublicEndpoint
	@PostMapping("/setup")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void setup(@Valid @RequestBody TokenPasswordRequest body) {
		accounts.completeWithToken(body.token(), body.newPassword());
	}

	/** Always 202, whether or not the account exists (no account enumeration). */
	@PublicEndpoint
	@PostMapping("/password-reset/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestReset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
		accounts.requestReset(body.schoolCode(), body.username(), request.getRemoteAddr());
	}

	@PublicEndpoint
	@PostMapping("/password-reset/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmReset(@Valid @RequestBody TokenPasswordRequest body) {
		accounts.completeWithToken(body.token(), body.newPassword());
	}

	@AnyAuthenticated
	@PostMapping("/step-up/request")
	StepUpService.CodeSent requestStepUp(HttpSession session) {
		return stepUp.sendCode(session);
	}

	@AnyAuthenticated
	@PostMapping("/step-up/verify")
	StepUpVerified verifyStepUp(@Valid @RequestBody StepUpVerifyRequest body, HttpSession session) {
		return new StepUpVerified(stepUp.verify(session, body.code()));
	}
}
