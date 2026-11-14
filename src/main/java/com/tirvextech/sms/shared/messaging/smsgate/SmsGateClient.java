package com.tirvextech.sms.shared.messaging.smsgate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the SMS Gate API (cloud relay api.sms-gate.app, a private server, or the local fake). Only the backend talks to
 * it; credentials never reach the browser. Docs: https://docs.sms-gate.app/integration/api/
 */
@Component
public class SmsGateClient {

	/** Webhook events the backend listens to. */
	public static final List<String> EVENTS = List.of("sms:sent", "sms:delivered", "sms:failed", "sms:cancelled",
			"sms:received", "system:ping", "app:started");

	public record Connection(String baseUrl, String username, String password) {

		String url(String path) {
			String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
			return base + path;
		}
	}

	/** Why a call failed: {@code retryable} = try again later (network, server busy); otherwise it will not succeed. */
	public static class GatewayException extends RuntimeException {

		private final int status;

		private final boolean retryable;

		GatewayException(int status, String message, boolean retryable) {
			super(message);
			this.status = status;
			this.retryable = retryable;
		}

		public int status() {
			return status;
		}

		public boolean retryable() {
			return retryable;
		}
	}

	public record MessageState(String state, String reason) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final JsonMapper json;

	SmsGateClient(JsonMapper json) {
		this.json = json;
	}

	/**
	 * Hands one SMS to the phone. {@code id} is ours: sending the same id again does not send a second SMS.
	 *
	 * @param ttlSeconds the phone drops the message if it cannot send it within this time (null = no limit)
	 */
	public void send(Connection c, String id, String phone, String text, Long ttlSeconds, Integer simNumber) {
		send(c, id, phone, text, ttlSeconds, simNumber, false);
	}

	/** @param urgent priority 100: the phone sends it even outside the app's working hours (a test the admin waits for) */
	public void send(Connection c, String id, String phone, String text, Long ttlSeconds, Integer simNumber,
			boolean urgent) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", id);
		body.put("textMessage", Map.of("text", text));
		body.put("phoneNumbers", List.of(phone));
		body.put("withDeliveryReport", true);
		if (ttlSeconds != null) {
			body.put("ttl", Math.max(ttlSeconds, 60));
		}
		if (simNumber != null) {
			body.put("simNumber", simNumber);
		}
		if (urgent) {
			body.put("priority", 100);
		}
		HttpResponse<String> res = call(c, "POST", "/messages", json.writeValueAsString(body));
		// 409: a message with this id already exists, i.e. an earlier hand-over got through. Nothing more to do.
		if (res.statusCode() == 409) {
			return;
		}
		check(res);
	}

	public MessageState state(Connection c, String id) {
		HttpResponse<String> res = call(c, "GET", "/messages/" + id, null);
		if (res.statusCode() == 404) {
			return new MessageState("NotFound", null);
		}
		check(res);
		JsonNode node = json.readTree(res.body());
		String reason = null;
		for (JsonNode r : node.path("recipients")) {
			reason = text(r.path("error"));
		}
		return new MessageState(text(node.path("state")), reason);
	}

	/** Asks the phone not to send a message. Best effort: a message already sent cannot be called back. */
	public void cancel(Connection c, String id) {
		HttpResponse<String> res = call(c, "DELETE", "/messages/" + id, null);
		if (res.statusCode() != 404 && res.statusCode() != 409) {
			check(res);
		}
	}

	/**
	 * Replaces this school's webhooks with one per event pointing at {@code url}. Earlier school-system webhooks (any
	 * address containing {@code marker}, e.g. from local testing through a tunnel) are removed; others are kept.
	 */
	public int registerWebhooks(Connection c, String url, String marker) {
		HttpResponse<String> list = call(c, "GET", "/webhooks", null);
		check(list);
		for (JsonNode hook : json.readTree(list.body())) {
			String existing = text(hook.path("url"));
			String hookId = text(hook.path("id"));
			if (hookId != null && existing != null && existing.contains(marker)) {
				check(call(c, "DELETE", "/webhooks/" + hookId, null));
			}
		}
		List<String> done = new ArrayList<>();
		for (String event : EVENTS) {
			check(call(c, "POST", "/webhooks", json.writeValueAsString(Map.of("url", url, "event", event))));
			done.add(event);
		}
		return done.size();
	}

	/** Proves the address and the login are right without sending anything. */
	public void verifyLogin(Connection c) {
		check(call(c, "GET", "/webhooks", null));
	}

	private HttpResponse<String> call(Connection c, String method, String path, String body) {
		String auth = Base64.getEncoder()
			.encodeToString((c.username() + ":" + c.password()).getBytes(StandardCharsets.UTF_8));
		HttpRequest.Builder req;
		try {
			req = HttpRequest.newBuilder(URI.create(c.url(path)));
		}
		catch (IllegalArgumentException e) {
			throw new GatewayException(0, "The SMS Gate server address is not valid", false);
		}
		req.timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + auth).header("Accept", "application/json");
		if (body != null) {
			req.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		}
		else {
			req.method(method, HttpRequest.BodyPublishers.noBody());
		}
		try {
			return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new GatewayException(0, "Could not reach the SMS Gate server", true);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GatewayException(0, "Interrupted", true);
		}
	}

	private static void check(HttpResponse<String> res) {
		int s = res.statusCode();
		if (s / 100 == 2) {
			return;
		}
		if (s == 401 || s == 403) {
			throw new GatewayException(s, "SMS Gate rejected the username or password", true);
		}
		if (s == 429 || s == 503 || s >= 500) {
			throw new GatewayException(s, "SMS Gate is busy (HTTP " + s + ")", true);
		}
		String detail = res.body() == null ? "" : res.body().replaceAll("\\s+", " ");
		throw new GatewayException(s, "SMS Gate refused the request (HTTP " + s + ")"
				+ (detail.isBlank() ? "" : ": " + detail.substring(0, Math.min(detail.length(), 200))), false);
	}

	static String text(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
	}
}
