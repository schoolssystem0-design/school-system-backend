package com.tirvextech.sms.iam.app;

import com.tirvextech.sms.iam.domain.AppUser;
import com.tirvextech.sms.iam.infra.AppUserRepository;
import com.tirvextech.sms.shared.messaging.MessageSender;
import com.tirvextech.sms.shared.messaging.OutgoingMessage;
import com.tirvextech.sms.shared.security.ActorPrincipal;
import com.tirvextech.sms.shared.security.ActorType;
import com.tirvextech.sms.shared.security.CurrentActor;
import com.tirvextech.sms.shared.security.StepUp;
import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.web.RateLimiter;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends and checks step-up codes for sensitive actions (FR 2.6, DECISIONS D-10). */
@Service
public class StepUpService {

	public record CodeSent(String sentTo) {
	}

	private final AppUserRepository users;

	private final MessageSender messages;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	StepUpService(AppUserRepository users, MessageSender messages, RateLimiter rateLimiter, Clock clock) {
		this.users = users;
		this.messages = messages;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CodeSent sendCode(HttpSession session) {
		ActorPrincipal actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			throw ApiException.businessRule("STEP_UP_NOT_NEEDED", "The super admin does not need step-up codes");
		}
		rateLimiter.check("stepup:" + actor.tenantSchoolId() + ":" + actor.actorId(), 5, Duration.ofMinutes(15));
		AppUser user = users.findById(actor.actorId()).orElseThrow(() -> ApiException.notFound("User"));
		if (user.getEmail() == null || user.getEmail().isBlank()) {
			throw ApiException.businessRule("STEP_UP_UNAVAILABLE",
					"Your account has no email address. Ask your administrator to add one.");
		}
		if (!messages.supports(OutgoingMessage.Channel.EMAIL)) {
			// Say so plainly instead of "try again later": retrying cannot help until the server is set up.
			throw ApiException.businessRule("EMAIL_NOT_SET_UP",
					"Email is not set up on the server, so verification codes cannot be sent. Ask the system owner to set it up.");
		}
		String code = StepUp.newChallenge(session, clock.instant());
		boolean sent = messages.send(OutgoingMessage.email(user.getEmail(), "Your verification code",
				"Your verification code is " + code + ". It expires in 5 minutes.\n\n"
						+ "If you did not request it, tell your administrator."));
		if (!sent) {
			throw ApiException.businessRule("STEP_UP_SEND_FAILED",
					"The code could not be emailed. Try again in a minute; if it keeps failing, tell the system owner.");
		}
		return new CodeSent(mask(user.getEmail()));
	}

	public Instant verify(HttpSession session, String code) {
		return StepUp.verify(session, code, clock.instant());
	}

	static String mask(String email) {
		int at = email.indexOf('@');
		if (at <= 1) {
			return "***" + email.substring(Math.max(at, 0));
		}
		return email.charAt(0) + "***" + email.substring(at);
	}
}
