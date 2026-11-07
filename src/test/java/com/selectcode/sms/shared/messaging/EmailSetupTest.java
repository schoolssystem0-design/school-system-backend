package com.selectcode.sms.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Email set-up: choosing the service, reporting problems, and failing safely (never throwing). */
class EmailSetupTest {

	private final JsonMapper json = JsonMapper.builder().build();

	private HttpServer server;

	private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

	private final AtomicInteger reply = new AtomicInteger(201);

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			Map<String, String> r = new ConcurrentHashMap<>();
			r.put("path", ex.getRequestURI().getPath());
			r.put("body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			r.put("api-key", String.valueOf(ex.getRequestHeaders().getFirst("api-key")));
			r.put("authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
			received.add(r);
			byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(reply.get(), out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static MockEnvironment production() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		env.setProperty("app.web-base-url", "https://school.example.com");
		return env;
	}

	@Test
	void nothingConfiguredInProductionIsReportedNotSilent() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		env.setProperty("app.web-base-url", "http://localhost:3000");
		EmailSetup setup = EmailSetup.create(env, null, json);
		assertThat(setup.ready()).isFalse();
		assertThat(setup.status().ready()).isFalse();
		assertThat(setup.status().problems()).anyMatch(p -> p.contains("No email service is set up"))
			.anyMatch(p -> p.contains("APP_WEB_BASE_URL"));
		assertThat(setup.send("a@b.pk", "Subject", "Body")).contains("No email service");
	}

	@Test
	void localDevelopmentNeedsNothing() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("local");
		env.setProperty("app.web-base-url", "http://localhost:3000");
		EmailSetup setup = EmailSetup.create(env, null, json);
		assertThat(setup.ready()).isTrue();
		assertThat(setup.status().problems()).isEmpty();
	}

	@Test
	void brevoApiSendsTheEmail() {
		MockEnvironment env = production();
		env.setProperty("app.mail.provider", "brevo");
		env.setProperty("app.mail.api-key", "xkeysib-test");
		env.setProperty("app.mail.api-url", url());
		env.setProperty("app.mail.from", "office@school.pk");
		env.setProperty("app.mail.from-name", "NNS School");
		EmailSetup setup = EmailSetup.create(env, null, json);
		assertThat(setup.status().ready()).isTrue();
		assertThat(setup.status().service()).isEqualTo("Brevo API");

		assertThat(setup.send("parent@mail.pk", "Your verification code", "Code 123456")).isNull();
		Map<String, String> r = received.getFirst();
		assertThat(r.get("path")).isEqualTo("/v3/smtp/email");
		assertThat(r.get("api-key")).isEqualTo("xkeysib-test");
		assertThat(r.get("body")).contains("\"email\":\"office@school.pk\"", "\"name\":\"NNS School\"",
				"\"email\":\"parent@mail.pk\"", "\"subject\":\"Your verification code\"", "\"textContent\":\"Code 123456\"");
	}

	@Test
	void resendApiRefusalIsExplainedNotThrown() {
		MockEnvironment env = production();
		env.setProperty("app.mail.provider", "resend");
		env.setProperty("app.mail.api-key", "re_test");
		env.setProperty("app.mail.api-url", url());
		env.setProperty("app.mail.from", "office@school.pk");
		EmailSetup setup = EmailSetup.create(env, null, json);
		reply.set(403);
		String error = setup.send("parent@mail.pk", "Subject", "Body");
		assertThat(error).contains("refused the API key");
		assertThat(received.getFirst().get("path")).isEqualTo("/emails");
		assertThat(received.getFirst().get("authorization")).isEqualTo("Bearer re_test");
		assertThat(setup.sendTest("me@school.pk").sent()).isFalse();
	}

	@Test
	void apiProviderWithoutKeyOrSenderIsAProblem() {
		MockEnvironment env = production();
		env.setProperty("app.mail.provider", "brevo");
		assertThat(EmailSetup.create(env, null, json).status().problems()).anyMatch(p -> p.contains("MAIL_API_KEY"));
		env.setProperty("app.mail.api-key", "k");
		EmailSetup noSender = EmailSetup.create(env, null, json);
		assertThat(noSender.ready()).isFalse();
		assertThat(noSender.status().problems()).anyMatch(p -> p.contains("MAIL_FROM"));
	}

	@Test
	void smtpFailureNeverThrowsAndSenderDefaultsToTheLogin() {
		JavaMailSenderImpl mail = new JavaMailSenderImpl() {
			@Override
			public void send(jakarta.mail.internet.MimeMessage message) {
				throw new MailSendException("535 Authentication failed: Username and Password not accepted");
			}
		};
		mail.setHost("smtp.gmail.com");
		mail.setPort(587);
		MockEnvironment env = production();
		env.setProperty("spring.mail.username", "school@gmail.com");
		env.setProperty("spring.mail.password", "app-password");
		EmailSetup setup = EmailSetup.create(env, mail, json);
		assertThat(setup.status().from()).isEqualTo("school@gmail.com");
		assertThat(setup.status().service()).isEqualTo("SMTP smtp.gmail.com:587");
		assertThat(setup.status().problems()).isEmpty();
		assertThat(setup.send("a@b.pk", "Subject", "Body")).contains("app password");
	}

	@Test
	void port465UsesSsl() {
		JavaMailSenderImpl mail = new JavaMailSenderImpl();
		mail.setHost("smtp.example.com");
		mail.setPort(465);
		MockEnvironment env = production();
		env.setProperty("spring.mail.username", "school@example.com");
		env.setProperty("spring.mail.password", "x");
		EmailSetup.create(env, mail, json);
		assertThat(mail.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
		assertThat(mail.getJavaMailProperties().getProperty("mail.smtp.starttls.required")).isEqualTo("false");
	}
}
