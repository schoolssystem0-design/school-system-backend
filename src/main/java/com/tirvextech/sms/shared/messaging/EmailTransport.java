package com.selectcode.sms.shared.messaging;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One way of sending email: SMTP (Gmail, Brevo SMTP, any mail server) or an email service's HTTPS API (Brevo, Resend),
 * which also works on hosts that block outgoing SMTP ports. Failures are reported as {@link EmailFailure} with a
 * message that is safe to show an administrator (never the email body: it may hold a one-time code).
 */
interface EmailTransport {

	/** e.g. "SMTP smtp.gmail.com:587" or "Brevo API"; shown to the system owner. */
	String describe();

	void send(String to, String subject, String body);

	/** Why an email was not sent, in words an administrator can act on. */
	final class EmailFailure extends RuntimeException {

		EmailFailure(String message) {
			super(message);
		}
	}

	/** SMTP through Spring's mail sender (configured with SPRING_MAIL_HOST, _PORT, _USERNAME, _PASSWORD). */
	record Smtp(JavaMailSender mail, String host, int port, String from, String fromName) implements EmailTransport {

		@Override
		public String describe() {
			return "SMTP " + host + ":" + port;
		}

		@Override
		public void send(String to, String subject, String body) {
			try {
				var message = mail.createMimeMessage();
				var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
				helper.setFrom(from, fromName);
				helper.setTo(to);
				helper.setSubject(subject);
				helper.setText(body, false);
				mail.send(message);
			}
			catch (MailException | jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
				throw new EmailFailure(smtpReason(e));
			}
		}

		private String smtpReason(Exception e) {
			String all = String.valueOf(e.getMessage()) + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
			String text = all.toLowerCase(java.util.Locale.ROOT);
			if (text.contains("authentication") || text.contains("535") || text.contains("username and password")) {
				return "The mail server refused the username or password (for Gmail use an app password)";
			}
			if (text.contains("timed out") || text.contains("timeout") || text.contains("connect")) {
				return "Could not connect to " + host + ":" + port
						+ " (the hosting may block email ports; use MAIL_PROVIDER=brevo or resend instead)";
			}
			if (text.contains("sender") || text.contains("from")) {
				return "The mail server refused the sender address " + from + " (use the account's own address)";
			}
			return "The mail server refused the email (" + e.getClass().getSimpleName() + ")";
		}
	}

	/** An email service's HTTPS API. */
	record Api(String provider, String baseUrl, String apiKey, String from, String fromName, JsonMapper json,
			HttpClient http) implements EmailTransport {

		static Api of(String provider, String baseUrl, String apiKey, String from, String fromName, JsonMapper json) {
			String url = baseUrl != null && !baseUrl.isBlank() ? baseUrl.replaceAll("/+$", "")
					: "brevo".equals(provider) ? "https://api.brevo.com" : "https://api.resend.com";
			return new Api(provider, url, apiKey, from, fromName, json,
					HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		}

		@Override
		public String describe() {
			return ("brevo".equals(provider) ? "Brevo" : "Resend") + " API";
		}

		@Override
		public void send(String to, String subject, String body) {
			HttpRequest.Builder request;
			String payload;
			if ("brevo".equals(provider)) {
				payload = json.writeValueAsString(Map.of("sender", Map.of("name", fromName, "email", from), "to",
						List.of(Map.of("email", to)), "subject", subject, "textContent", body));
				request = HttpRequest.newBuilder(URI.create(baseUrl + "/v3/smtp/email")).header("api-key", apiKey);
			}
			else {
				payload = json.writeValueAsString(Map.of("from", fromName + " <" + from + ">", "to", List.of(to), "subject",
						subject, "text", body));
				request = HttpRequest.newBuilder(URI.create(baseUrl + "/emails")).header("Authorization", "Bearer " + apiKey);
			}
			HttpResponse<String> response;
			try {
				response = http.send(request.timeout(Duration.ofSeconds(20))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
					.build(), HttpResponse.BodyHandlers.ofString());
			}
			catch (IOException e) {
				throw new EmailFailure("Could not reach the " + describe());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new EmailFailure("Interrupted");
			}
			int status = response.statusCode();
			if (status / 100 == 2) {
				return;
			}
			if (status == 401 || status == 403) {
				throw new EmailFailure("The " + describe() + " refused the API key, or the sender " + from
						+ " is not verified with it");
			}
			String detail = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
			throw new EmailFailure("The " + describe() + " refused the email (HTTP " + status + ")"
					+ (detail.isBlank() ? "" : ": " + detail.substring(0, Math.min(200, detail.length()))));
		}
	}
}
