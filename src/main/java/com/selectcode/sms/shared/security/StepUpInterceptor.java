package com.selectcode.sms.shared.security;

import com.selectcode.sms.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link RequiresStepUp} on controller methods (or whole controllers). */
class StepUpInterceptor implements HandlerInterceptor {

	private final Clock clock;

	StepUpInterceptor(Clock clock) {
		this.clock = clock;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		boolean required = method.hasMethodAnnotation(RequiresStepUp.class)
				|| method.getBeanType().isAnnotationPresent(RequiresStepUp.class);
		if (!required || CurrentActor.isSuperAdmin()) {
			return true;
		}
		if (!StepUp.isActive(request.getSession(false), clock.instant())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_REQUIRED",
					"Confirm it's you: enter the code we send to your email");
		}
		return true;
	}
}
