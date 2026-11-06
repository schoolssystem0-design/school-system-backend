package com.selectcode.sms.shared.messaging.smsgate;

import com.selectcode.sms.shared.security.PublicEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local development only: a pretend SMS Gate server and phone, so the whole SMS flow (hand-over, signed delivery
 * webhooks, replies, health pings) can be used and tested without a real phone. Point a school's SMS phone settings at
 * {@code http://localhost:8080/api/v1/dev/sms-gate/3rdparty/v1}; any username works, password "wrong" is refused.
 *
 * <p>Numbers ending in 0000 fail on the "phone"; numbers ending in 1111 are sent but never delivered. A phone set
 * offline keeps messages pending and sends no webhooks.
 */
@Profile("local")
@RestController
@RequestMapping(FakeSmsGate.BASE)
class FakeSmsGate {

	static final String BASE = "/api/v1/dev/sms-gate";

	record Message(String id, String user, String phone, String text, Instant at, String state, String reason) {

		Message with(String newState, String newReason) {
			return new Message(id, user, phone, text, at, newState, newReason);
		}
	}

	record Hook(String id, String user, String url, String event) {
	}

	private final Map<String, Message> messages = new ConcurrentHashMap<>();

	private final Map<String, Hook> hooks = new ConcurrentHashMap<>();

	private final Map<String, Boolean> offline = new ConcurrentHashMap<>();

	private final HttpClient http = HttpClient.newHttpClient();

	private final SmsGateways gateways;

	private final JsonMapper json;

	FakeSmsGate(SmsGateways gateways, JsonMapper json) {
		this.gateways = gateways;
		this.json = json;
	}

	private static String user(String authorization) {
		if (authorization == null || !authorization.startsWith("Basic ")) {
			return null;
		}
		String pair = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
		int colon = pair.indexOf(':');
		return colon < 0 || "wrong".equals(pair.substring(colon + 1)) ? null : pair.substring(0, colon);
	}

	@PublicEndpoint
	@PostMapping("/3rdparty/v1/messages")
	ResponseEntity<Object> send(@RequestHeader(value = "Authorization", required = false) String auth,
			@RequestBody JsonNode body) {
		String user = user(auth);
		if (user == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String id = body.path("id").isMissingNode() ? UUID.randomUUID().toString() : body.path("id").asString();
		if (messages.containsKey(id)) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "message already exists"));
		}
		String phone = body.path("phoneNumbers").path(0).asString();
		Message m = new Message(id, user, phone, body.path("textMessage").path("text").asString(), Instant.now(), "Pending",
				null);
		messages.put(id, m);
		if (!offline.getOrDefault(user, false)) {
			later(1, () -> progress(id));
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id, "state", "Pending"));
	}

	private void progress(String id) {
		Message m = messages.get(id);
		if (m == null || !"Pending".equals(m.state()) || offline.getOrDefault(m.user(), false)) {
			return;
		}
		if (m.phone().endsWith("0000")) {
			messages.put(id, m.with("Failed", "RESULT_ERROR_GENERIC_FAILURE"));
			fire(m.user(), "sms:failed", Map.of("messageId", id, "recipient", m.phone(), "reason",
					"RESULT_ERROR_GENERIC_FAILURE", "failedAt", Instant.now().toString()));
			return;
		}
		messages.put(id, m.with("Sent", null));
		fire(m.user(), "sms:sent", Map.of("messageId", id, "recipient", m.phone(), "partsCount", 1, "sentAt",
				Instant.now().toString()));
		if (!m.phone().endsWith("1111")) {
			later(1, () -> {
				messages.computeIfPresent(id, (k, v) -> v.with("Delivered", null));
				fire(m.user(), "sms:delivered", Map.of("messageId", id, "recipient", m.phone(), "deliveredAt",
						Instant.now().toString()));
			});
		}
	}

	@PublicEndpoint
	@GetMapping("/3rdparty/v1/messages/{id}")
	ResponseEntity<Object> state(@RequestHeader(value = "Authorization", required = false) String auth,
			@PathVariable String id) {
		if (user(auth) == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Message m = messages.get(id);
		if (m == null) {
			return ResponseEntity.notFound().build();
		}
		Map<String, Object> recipient = new LinkedHashMap<>();
		recipient.put("phoneNumber", m.phone());
		recipient.put("state", m.state());
		if (m.reason() != null) {
			recipient.put("error", m.reason());
		}
		return ResponseEntity.ok(Map.of("id", id, "state", m.state(), "recipients", List.of(recipient)));
	}

	@PublicEndpoint
	@DeleteMapping("/3rdparty/v1/messages/{id}")
	ResponseEntity<Object> cancel(@RequestHeader(value = "Authorization", required = false) String auth,
			@PathVariable String id) {
		if (user(auth) == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Message m = messages.get(id);
		if (m == null) {
			return ResponseEntity.notFound().build();
		}
		if (!"Pending".equals(m.state())) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		messages.put(id, m.with("Cancelled", null));
		fire(m.user(), "sms:cancelled", Map.of("messageId", id, "recipient", m.phone(), "cancelledAt",
				Instant.now().toString()));
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id, "state", "Cancelling"));
	}

	@PublicEndpoint
	@GetMapping("/3rdparty/v1/webhooks")
	ResponseEntity<Object> webhooks(@RequestHeader(value = "Authorization", required = false) String auth) {
		String user = user(auth);
		if (user == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		return ResponseEntity.ok(hooks.values().stream().filter(h -> h.user().equals(user))
			.map(h -> Map.of("id", h.id(), "url", h.url(), "event", h.event())).toList());
	}

	@PublicEndpoint
	@PostMapping("/3rdparty/v1/webhooks")
	ResponseEntity<Object> addWebhook(@RequestHeader(value = "Authorization", required = false) String auth,
			@RequestBody JsonNode body) {
		String user = user(auth);
		if (user == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Hook h = new Hook(UUID.randomUUID().toString(), user, body.path("url").asString(), body.path("event").asString());
		hooks.put(h.id(), h);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", h.id(), "url", h.url(), "event", h.event()));
	}

	@PublicEndpoint
	@DeleteMapping("/3rdparty/v1/webhooks/{id}")
	ResponseEntity<Void> deleteWebhook(@RequestHeader(value = "Authorization", required = false) String auth,
			@PathVariable String id) {
		if (user(auth) == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		hooks.remove(id);
		return ResponseEntity.noContent().build();
	}

	// ------------------------------------------------------- test controls

	record Reply(String user, String from, String text) {
	}

	record Ping(String user, Integer battery, Boolean charging) {
	}

	record Offline(String user, boolean offline) {
	}

	/** A parent replies to the phone (e.g. STOP). */
	@PublicEndpoint
	@PostMapping("/simulate/reply")
	void reply(@RequestBody Reply r) {
		fire(r.user(), "sms:received", Map.of("messageId", UUID.randomUUID().toString(), "message", r.text(), "sender",
				r.from(), "receivedAt", Instant.now().toString()));
	}

	/** The phone checks in with its battery state. */
	@PublicEndpoint
	@PostMapping("/simulate/ping")
	void ping(@RequestBody Ping p) {
		Map<String, Object> checks = new LinkedHashMap<>();
		checks.put("battery:level", Map.of("status", "pass", "observedValue", p.battery() == null ? 90 : p.battery(),
				"observedUnit", "percent"));
		checks.put("battery:charging", Map.of("status", "pass", "observedValue",
				p.charging() == null || p.charging() ? 1 : 0, "observedUnit", "boolean"));
		fire(p.user(), "system:ping", Map.of("health", Map.of("status", "pass", "checks", checks)));
	}

	/** Switches the pretend phone off or on. Switching it on sends what was waiting. */
	@PublicEndpoint
	@PostMapping("/simulate/offline")
	void offline(@RequestBody Offline o) {
		offline.put(o.user(), o.offline());
		if (!o.offline()) {
			messages.values().stream().filter(m -> m.user().equals(o.user()) && "Pending".equals(m.state()))
				.forEach(m -> later(1, () -> progress(m.id())));
		}
	}

	/** What the pretend phone has sent, newest first. */
	@PublicEndpoint
	@GetMapping("/messages")
	List<Message> sent() {
		List<Message> all = new ArrayList<>(messages.values());
		all.sort((a, b) -> b.at().compareTo(a.at()));
		return all;
	}

	private void fire(String user, String event, Map<String, Object> payload) {
		String key = gateways.signingKeyForUsername(user);
		if (key == null) {
			return;
		}
		for (Hook h : hooks.values()) {
			if (!h.user().equals(user) || !h.event().equals(event)) {
				continue;
			}
			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("deviceId", "fake-" + user);
			envelope.put("event", event);
			envelope.put("id", UUID.randomUUID().toString());
			envelope.put("payload", payload);
			envelope.put("webhookId", h.id());
			byte[] body = json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
			String ts = String.valueOf(Instant.now().getEpochSecond());
			String signature = HexFormat.of().formatHex(SmsGateWebhookController.sign(key, body, ts));
			HttpRequest request = HttpRequest.newBuilder(URI.create(h.url())).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").header("X-Signature", signature).header("X-Timestamp", ts)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
			http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
		}
	}

	private static void later(int seconds, Runnable work) {
		CompletableFuture.runAsync(work, CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS));
	}
}
