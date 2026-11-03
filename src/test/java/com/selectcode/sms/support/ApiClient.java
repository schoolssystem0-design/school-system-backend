package com.selectcode.sms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Totp;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * A browser-like client for MockMvc: keeps cookies (session + CSRF) between requests and sends the CSRF token on
 * state-changing calls, like the Next.js app.
 */
public class ApiClient {

	private final MockMvc mvc;

	private final Map<String, Cookie> cookies = new LinkedHashMap<>();

	private String csrfToken;

	private String yearHeader;

	public ApiClient(MockMvc mvc) {
		this.mvc = mvc;
	}

	public ResultActions get(String url) throws Exception {
		return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), false);
	}

	public ResultActions post(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), json), true);
	}

	/** POST with an Idempotency-Key header (payments, bill runs, reminders). */
	public ResultActions postWithKey(String url, String json, String idempotencyKey) throws Exception {
		var builder = withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), json);
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return send(builder, true);
	}

	public ResultActions post(String url) throws Exception {
		return post(url, null);
	}

	public ResultActions put(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), json), true);
	}

	public ResultActions patch(String url, String json) throws Exception {
		return send(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url), json),
				true);
	}

	public ResultActions delete(String url) throws Exception {
		return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url), true);
	}

	public ResultActions upload(String url, String purpose, String filename, byte[] content) throws Exception {
		var builder = multipart(url).file(new MockMultipartFile("file", filename, "application/octet-stream", content))
			.param("purpose", purpose);
		return send(builder, true);
	}

	/** Send {@code X-Academic-Year} on every request (null = current year). */
	public ApiClient year(Long yearId) {
		this.yearHeader = yearId == null ? null : String.valueOf(yearId);
		return this;
	}

	public ResultActions login(String schoolCode, String username, String password) throws Exception {
		return post("/api/v1/auth/login", """
				{"schoolCode":"%s","username":"%s","password":"%s"}""".formatted(schoolCode, username, password));
	}

	public ResultActions platformLogin(String email, String password, String totpSecret) throws Exception {
		String code = totpSecret == null ? "" : Totp.codeAt(totpSecret, Instant.now());
		return post("/api/v1/platform/auth/login", """
				{"email":"%s","password":"%s","totpCode":"%s"}""".formatted(email, password, code));
	}

	/** Requests a step-up code (sent to the dev outbox in tests) and verifies it. */
	public void stepUp(DevOutbox outbox) throws Exception {
		post("/api/v1/auth/step-up/request").andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		String body = outbox.entries().getFirst().message().body();
		Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(body);
		if (!matcher.find()) {
			throw new IllegalStateException("No code in step-up message");
		}
		post("/api/v1/auth/step-up/verify", "{\"code\":\"" + matcher.group(1) + "\"}").andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
	}

	public static <T> T json(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	public void forgetCookies() {
		cookies.clear();
		csrfToken = null;
	}

	public Cookie sessionCookie() {
		return cookies.get("SMS_SESSION");
	}

	public void useSessionCookie(Cookie cookie) {
		cookies.put("SMS_SESSION", cookie);
	}

	private ResultActions send(AbstractMockHttpServletRequestBuilder<?> builder, boolean unsafe) throws Exception {
		if (unsafe) {
			ensureCsrf();
			builder.header("X-XSRF-TOKEN", csrfToken);
		}
		if (yearHeader != null) {
			builder.header("X-Academic-Year", yearHeader);
		}
		if (!cookies.isEmpty()) {
			builder.cookie(cookies.values().toArray(new Cookie[0]));
		}
		ResultActions result = mvc.perform(builder);
		remember(result.andReturn());
		return result;
	}

	private void ensureCsrf() throws Exception {
		if (csrfToken != null) {
			return;
		}
		var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf");
		if (!cookies.isEmpty()) {
			builder.cookie(cookies.values().toArray(new Cookie[0]));
		}
		MvcResult result = mvc.perform(builder).andReturn();
		remember(result);
		csrfToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private void remember(MvcResult result) {
		for (Cookie cookie : result.getResponse().getCookies()) {
			if (cookie.getMaxAge() == 0) {
				cookies.remove(cookie.getName());
				if ("XSRF-TOKEN".equals(cookie.getName())) {
					csrfToken = null;
				}
			}
			else {
				cookies.put(cookie.getName(), cookie);
			}
		}
	}

	private static AbstractMockHttpServletRequestBuilder<?> withJson(AbstractMockHttpServletRequestBuilder<?> builder, String json) {
		if (json != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return builder;
	}
}
