package com.tirvextech.sms.shared.messaging;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration(proxyBeanMethods = false)
class MessagingConfig {

	private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

	/** How email is sent (SMTP or an email API), checked at start-up and shown to the system owner. */
	@Bean
	EmailSetup emailSetup(Environment environment, ObjectProvider<JavaMailSender> mailSender, JsonMapper json) {
		return EmailSetup.create(environment, mailSender.getIfAvailable(), json);
	}

	/**
	 * Local/test: keep messages in memory (visible at GET /api/v1/dev/outbox) so one-time codes can be used without a
	 * mail server. Elsewhere: email through {@link EmailSetup} (SMTP or an email API); without one, emails are not sent
	 * and the start-up log and the system owner's page say why.
	 */
	@Bean
	@org.springframework.context.annotation.Primary
	MessageSender messageSender(Environment environment, EmailSetup email, ObjectProvider<DevOutbox> devOutbox) {
		if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
			return devOutbox.getObject();
		}
		String smsUrl = environment.getProperty("app.sms.url-template", "");
		HttpSmsSender sms = smsUrl.isBlank() ? null
				: new HttpSmsSender(smsUrl, environment.getProperty("app.sms.failure-text"));
		MessageSender real = new MessageSender() {
			// Never throws: a mail server that is down must not undo the business change that sent the email
			// (creating a school, a password reset request).
			@Override
			public boolean send(OutgoingMessage message) {
				if (message.channel() == OutgoingMessage.Channel.SMS && sms != null) {
					return sms.send(message);
				}
				if (message.channel() == OutgoingMessage.Channel.EMAIL && email.ready()) {
					return email.send(message.recipient(), message.subject(), message.body()) == null;
				}
				log.warn("No provider configured for {} messages; message not sent", message.channel());
				return false;
			}

			@Override
			public boolean supports(OutgoingMessage.Channel channel) {
				return channel == OutgoingMessage.Channel.SMS ? sms != null : email.ready();
			}
		};
		// Staging: messages only to the allow-list (comma-separated phone numbers and emails)
		String allowList = environment.getProperty("app.messaging.allow-list", "");
		return allowList.isBlank() ? real
				: new AllowListSender(real, java.util.Arrays.stream(allowList.split(","))
					.map(String::trim)
					.collect(java.util.stream.Collectors.toSet()));
	}

	/** In-memory outbox for local development and tests. Never active in staging/production. */
	@Profile({ "local", "test" })
	@Bean
	DevOutbox devOutbox() {
		return new DevOutbox();
	}

	@Profile("local")
	@RestController
	static class DevOutboxController {

		private final DevOutbox outbox;

		DevOutboxController(DevOutbox outbox) {
			this.outbox = outbox;
		}

		/** Local development only: read emails/SMS the app would have sent (setup links, codes). */
		@com.tirvextech.sms.shared.security.PublicEndpoint
		@GetMapping("/api/v1/dev/outbox")
		List<DevOutbox.OutboxEntry> outbox() {
			return outbox.entries();
		}
	}
}
