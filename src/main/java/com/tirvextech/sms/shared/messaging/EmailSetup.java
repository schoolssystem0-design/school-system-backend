package com.tirvextech.sms.shared.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.json.JsonMapper;

/**
 * How this server sends email (verification codes, password resets, setup links, alerts), and what is wrong with that
 * set-up. Checked at start-up (problems are logged loudly) and shown to the system owner, who can send a test email.
 *
 * <p>Configuration (environment variables):
 * <ul>
 * <li>SMTP: SPRING_MAIL_HOST, SPRING_MAIL_PORT (587 STARTTLS or 465 SSL), SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD;</li>
 * <li>or an HTTPS email API (works where SMTP ports are blocked): MAIL_PROVIDER=brevo or resend, MAIL_API_KEY;</li>
 * <li>MAIL_FROM (defaults to the SMTP username), MAIL_FROM_NAME, and APP_WEB_BASE_URL for links in emails.</li>
 * </ul>
 */
public class EmailSetup {

	private static final Logger log = LoggerFactory.getLogger(EmailSetup.class);

	public record EmailStatus(boolean local, boolean ready, String service, String from, String fromName,
			String webBaseUrl, List<String> problems) {
	}

	public record EmailTestResult(boolean sent, String error) {
	}

	private final EmailTransport transport;

	private final boolean local;

	private final String from;

	private final String fromName;

	private final String webBaseUrl;

	private final List<String> problems;

	private EmailSetup(EmailTransport transport, boolean local, String from, String fromName, String webBaseUrl,
			List<String> problems) {
		this.transport = transport;
		this.local = local;
		this.from = from;
		this.fromName = fromName;
		this.webBaseUrl = webBaseUrl;
		this.problems = List.copyOf(problems);
	}

	static EmailSetup create(Environment env, JavaMailSender mail, JsonMapper json) {
		boolean local = env.acceptsProfiles(Profiles.of("local", "test"));
		List<String> problems = new ArrayList<>();
		String provider = text(env.getProperty("app.mail.provider")).toLowerCase(Locale.ROOT);
		String smtpUser = text(env.getProperty("spring.mail.username"));
		String from = text(env.getProperty("app.mail.from"));
		if (from.isEmpty() && smtpUser.contains("@")) {
			from = smtpUser;
		}
		String fromName = text(env.getProperty("app.mail.from-name"));
		if (fromName.isEmpty()) {
			fromName = "Tirvex Tech School System";
		}
		String webBaseUrl = text(env.getProperty("app.web-base-url")).replaceAll("/+$", "");

		EmailTransport transport = null;
		if (!provider.isEmpty() && !"smtp".equals(provider)) {
			String apiKey = text(env.getProperty("app.mail.api-key"));
			if (!"brevo".equals(provider) && !"resend".equals(provider)) {
				problems.add("MAIL_PROVIDER must be brevo, resend or smtp (it is \"" + provider + "\")");
			}
			else if (apiKey.isEmpty()) {
				problems.add("MAIL_PROVIDER is " + provider + " but MAIL_API_KEY is not set");
			}
			else {
				transport = EmailTransport.Api.of(provider, env.getProperty("app.mail.api-url"), apiKey, from, fromName, json);
			}
		}
		else if (mail != null) {
			int port = mail instanceof JavaMailSenderImpl impl ? impl.getPort() : 587;
			String host = mail instanceof JavaMailSenderImpl impl ? impl.getHost() : env.getProperty("spring.mail.host");
			if (mail instanceof JavaMailSenderImpl impl && port == 465) {
				// Port 465 is SSL from the first byte, not STARTTLS.
				impl.getJavaMailProperties().put("mail.smtp.ssl.enable", "true");
				impl.getJavaMailProperties().put("mail.smtp.starttls.enable", "false");
				impl.getJavaMailProperties().put("mail.smtp.starttls.required", "false");
			}
			if (smtpUser.isEmpty() || text(env.getProperty("spring.mail.password")).isEmpty()) {
				problems.add("SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD must be set for the mail server");
			}
			transport = new EmailTransport.Smtp(mail, host, port, from, fromName);
		}
		else if (!local) {
			problems.add("No email service is set up, so verification codes, password resets and setup links cannot be"
					+ " emailed. Set SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD, or MAIL_PROVIDER and MAIL_API_KEY");
		}
		if (transport != null && from.isEmpty()) {
			problems.add("MAIL_FROM (the sender address) is not set");
			transport = null;
		}
		else if (transport != null && !from.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			problems.add("MAIL_FROM is not an email address: " + from);
			transport = null;
		}
		if (!local && (webBaseUrl.isEmpty() || webBaseUrl.contains("localhost") || webBaseUrl.contains("127.0.0.1"))) {
			problems.add("APP_WEB_BASE_URL is " + (webBaseUrl.isEmpty() ? "not set" : webBaseUrl)
					+ ": links in emails (password reset, account setup) would not open. Set it to the website address");
		}
		else if (!local && !webBaseUrl.startsWith("https://")) {
			problems.add("APP_WEB_BASE_URL should start with https:// (it is " + webBaseUrl + ")");
		}
		return new EmailSetup(transport, local, from, fromName, webBaseUrl, problems);
	}

	/** Email can be sent (locally: to the dev outbox). */
	public boolean ready() {
		return local || transport != null;
	}

	/** Sends one email; never throws. @return null when sent, otherwise why not */
	String send(String to, String subject, String body) {
		if (transport == null) {
			return "No email service is set up on the server";
		}
		try {
			transport.send(to, subject, body);
			return null;
		}
		catch (EmailTransport.EmailFailure e) {
			log.warn("Email '{}' not sent via {}: {}", subject, transport.describe(), e.getMessage());
			return e.getMessage();
		}
		catch (RuntimeException e) {
			log.warn("Email '{}' not sent via {}: {}", subject, transport.describe(), e.getClass().getSimpleName());
			return "Unexpected error (" + e.getClass().getSimpleName() + ")";
		}
	}

	public EmailStatus status() {
		String service = local && transport == null ? "Development outbox (local only)"
				: transport == null ? null : transport.describe();
		return new EmailStatus(local, ready() && problems.isEmpty(), service, from.isEmpty() ? null : from, fromName,
				webBaseUrl, problems);
	}

	/** Sends a test email straight away and says exactly what went wrong if it fails. */
	public EmailTestResult sendTest(String to) {
		if (local && transport == null) {
			return new EmailTestResult(true, null);
		}
		String error = send(to, "Test email from the school system",
				"This is a test from the school management system.\n\nIf you received it, verification codes, password"
						+ " resets and account setup links can be emailed.\n\nLinks in emails open: " + webBaseUrl);
		return new EmailTestResult(error == null, error);
	}

	@EventListener(ApplicationReadyEvent.class)
	void reportAtStartup() {
		if (local) {
			return;
		}
		if (problems.isEmpty()) {
			log.info("Email: sending through {} as {}; links open {}", transport.describe(), from, webBaseUrl);
		}
		else {
			problems.forEach(p -> log.error("EMAIL SET-UP PROBLEM: {}", p));
		}
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}
}
