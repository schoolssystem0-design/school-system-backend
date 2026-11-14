package com.selectcode.sms.shared.security;

import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * For every authenticated request: sets the school (tenant) from the session principal, re-validates the principal
 * and loads its current permissions, and blocks everything except password change while a password change is
 * required. Runs inside the Spring Security chain, before authorization.
 */
public class RequestPrincipalFilter extends OncePerRequestFilter {

	public static final String MUST_CHANGE_PASSWORD_ATTRIBUTE = "sms.mustChangePassword";

	/** Paths still allowed while the user must change their password. */
	private static final List<String> PASSWORD_CHANGE_ALLOWED = List.of("/api/v1/auth/");

	private final List<PrincipalRefresher> refreshers;

	private final JsonMapper json;

	public RequestPrincipalFilter(List<PrincipalRefresher> refreshers, JsonMapper json) {
		this.refreshers = refreshers;
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof ActorPrincipal principal)) {
			chain.doFilter(request, response);
			return;
		}
		Long schoolId = principal.tenantSchoolId();
		if (schoolId != null) {
			TenantContext.set(schoolId);
		}
		try {
			PrincipalRefresher.Refreshed refreshed = refreshers.stream()
				.filter(r -> r.supports(principal))
				.findFirst()
				.map(r -> r.refresh(principal))
				.orElse(PrincipalRefresher.Refreshed.invalid());

			if (!refreshed.valid()) {
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				SecurityContextHolder.clearContext();
				write(response, Problems.of(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
						"Your session has ended. Please sign in again."));
				return;
			}

			// Fresh authorities for this request only (not saved to the session).
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
					refreshed.authorities()));
			SecurityContextHolder.setContext(context);
			request.setAttribute(MUST_CHANGE_PASSWORD_ATTRIBUTE, refreshed.mustChangePassword());

			if (refreshed.mustChangePassword()
					&& PASSWORD_CHANGE_ALLOWED.stream().noneMatch(request.getRequestURI()::startsWith)) {
				write(response, Problems.of(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
						"Change your password to continue"));
				return;
			}
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}

	private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), problem);
	}
}
