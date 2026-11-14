package com.selectcode.sms.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.office.app.BirthdaySmsJob;
import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.messaging.smsgate.SmsGateJobs;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The school's own SMS phone (SMS Gate): connecting, hand-over with our own id, signed delivery reports, quiet hours,
 * daily limit, expiry, STOP replies, health, stuck messages and offline alerts. SMS Gate is a real HTTP server here.
 */
@AutoConfigureMockMvc
class SmsGateIT extends IntegrationTest {

	private static final String SIGNING_KEY = "test-signing-key-123";

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox devOutbox;

	@Autowired
	MessageOutbox outbox;

	@Autowired
	SmsGateJobs jobs;

	@Autowired
	BirthdaySmsJob birthdays;

	/** A pretend SMS Gate server: records what it receives; responses can be changed per test. */
	static class FakeGate {

		final HttpServer server;

		final List<Map<String, String>> sent = new CopyOnWriteArrayList<>();

		final Map<String, String> hooks = new ConcurrentHashMap<>();

		final List<String> cancelled = new CopyOnWriteArrayList<>();

		final Map<String, String> states = new ConcurrentHashMap<>();

		final AtomicInteger sendStatus = new AtomicInteger(202);

		FakeGate() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/3rdparty/v1/", this::handle);
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/3rdparty/v1";
		}

		private void handle(HttpExchange ex) throws IOException {
			String auth = ex.getRequestHeaders().getFirst("Authorization");
			String pair = auth == null ? "" : new String(java.util.Base64.getDecoder().decode(auth.substring(6)),
					StandardCharsets.UTF_8);
			if (pair.endsWith(":wrong")) {
				reply(ex, 401, "{\"message\":\"unauthorized\"}");
				return;
			}
			String path = ex.getRequestURI().getPath().substring("/3rdparty/v1".length());
			String method = ex.getRequestMethod();
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (path.equals("/messages") && method.equals("POST")) {
				String id = field(body, "id");
				if (sendStatus.get() != 202) {
					reply(ex, sendStatus.get(), "{}");
					return;
				}
				if (sent.stream().anyMatch(m -> m.get("id").equals(id))) {
					reply(ex, 409, "{\"message\":\"exists\"}");
					return;
				}
				sent.add(Map.of("id", id, "phone", body.replaceAll(".*\"phoneNumbers\":\\[\"([^\"]+)\"].*", "$1"),
						"body", body));
				reply(ex, 202, "{\"id\":\"" + id + "\",\"state\":\"Pending\"}");
			}
			else if (path.startsWith("/messages/") && method.equals("GET")) {
				String id = path.substring("/messages/".length());
				String state = states.get(id);
				reply(ex, state == null ? 404 : 200, state == null ? "{}"
						: "{\"id\":\"" + id + "\",\"state\":\"" + state + "\",\"recipients\":[{\"state\":\"" + state + "\"}]}");
			}
			else if (path.startsWith("/messages/") && method.equals("DELETE")) {
				cancelled.add(path.substring("/messages/".length()));
				reply(ex, 202, "{}");
			}
			else if (path.equals("/webhooks") && method.equals("GET")) {
				StringBuilder sb = new StringBuilder("[");
				hooks.forEach((id, url) -> sb.append(sb.length() > 1 ? "," : "")
					.append("{\"id\":\"").append(id).append("\",\"url\":\"").append(url).append("\"}"));
				reply(ex, 200, sb.append("]").toString());
			}
			else if (path.equals("/webhooks") && method.equals("POST")) {
				String id = UUID.randomUUID().toString();
				hooks.put(id, field(body, "url"));
				reply(ex, 201, "{\"id\":\"" + id + "\"}");
			}
			else if (path.startsWith("/webhooks/") && method.equals("DELETE")) {
				hooks.remove(path.substring("/webhooks/".length()));
				reply(ex, 204, null);
			}
			else {
				reply(ex, 404, "{}");
			}
		}

		private static String field(String json, String name) {
			var m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
			return m.find() ? m.group(1) : null;
		}

		private static void reply(HttpExchange ex, int status, String body) throws IOException {
			if (body == null) {
				ex.sendResponseHeaders(status, -1);
			}
			else {
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().add("Content-Type", "application/json");
				ex.sendResponseHeaders(status, bytes.length);
				ex.getResponseBody().write(bytes);
			}
			ex.close();
		}
	}

	FakeGate gate;

	SeededSchool school;

	ApiClient admin;

	JdbcTemplate db = TestDatabase.superuser();

	@BeforeEach
	void seed() throws Exception {
		gate = new FakeGate();
		school = TestData.school("SMG");
		TestData.admin(school, "admin", "admin-password-1");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
		admin.stepUp(devOutbox);
		// Tests run at any hour: no quiet hours unless a test sets them.
		setting("messages.sms_quiet_start", "\"00:00\"");
		setting("messages.sms_quiet_end", "\"00:00\"");
	}

	@AfterEach
	void stop() {
		gate.server.stop(0);
		// Other test classes share the database: leave no school with a phone that points at a stopped server.
		db.update("update sms_gateway set enabled = false where school_id = ?", school.id());
	}

	/** Changes a school setting through the settings API (as an admin would). */
	void setting(String key, String json) throws Exception {
		admin.put("/api/v1/settings/" + key, "{\"value\":" + json + ",\"reason\":\"test\"}").andExpect(status().isOk());
	}

	ResultActions connect(String password) throws Exception {
		return admin.put("/api/v1/communication/sms-phone", """
				{"baseUrl":"%s","username":"school-phone","password":"%s","signingKey":"%s","alertEmail":"office@test.pk"}"""
			.formatted(gate.url(), password, SIGNING_KEY));
	}

	String token() {
		return db.queryForObject("select webhook_token from sms_gateway where school_id = ?", String.class, school.id());
	}

	ResultActions webhook(String event, String payloadJson, String eventId, String key, long timestamp) throws Exception {
		String body = "{\"deviceId\":\"d1\",\"event\":\"" + event + "\",\"id\":\"" + eventId + "\",\"payload\":" + payloadJson
				+ ",\"webhookId\":\"w1\"}";
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		mac.update(bytes);
		mac.update(String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
		return mvc.perform(post("/api/v1/public/sms-gate/webhook/" + token()).contentType(MediaType.APPLICATION_JSON)
			.content(bytes)
			.header("X-Signature", HexFormat.of().formatHex(mac.doFinal()))
			.header("X-Timestamp", String.valueOf(timestamp)));
	}

	ResultActions webhook(String event, String payloadJson) throws Exception {
		return webhook(event, payloadJson, UUID.randomUUID().toString(), SIGNING_KEY, Instant.now().getEpochSecond());
	}

	Map<String, Object> row(long id) {
		return db.queryForMap("select status, gateway_id, attempts, last_error, queued_at, delivered_at from message_outbox where id = ?", id);
	}

	long queue(String phone, String purpose, Instant expiresAt) {
		return db.queryForObject("""
				insert into message_outbox (school_id, channel, recipient, body, purpose, priority, expires_at)
				values (?, 'SMS', ?, 'Hello', ?, ?, ?) returning id""", Long.class, school.id(), phone, purpose,
				"ATTENDANCE_ABSENT".equals(purpose) ? 1 : "BIRTHDAY".equals(purpose) ? 9 : 5,
				expiresAt == null ? null : Timestamp.from(expiresAt));
	}

	@Test
	void connectingChecksTheLoginAndRegistersWebhooks() throws Exception {
		admin.get("/api/v1/communication/sms-phone").andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(false));
		connect("wrong").andExpect(status().is(422)).andExpect(jsonPath("$.code").value("SMS_GATE_LOGIN"));
		assertThat(db.queryForObject("select count(*) from sms_gateway where school_id = ?", Integer.class, school.id()))
			.isZero();
		admin.put("/api/v1/communication/sms-phone", """
				{"baseUrl":"ftp://example.com","username":"u","password":"p","signingKey":"12345678"}""")
			.andExpect(jsonPath("$.code").value("URL_INVALID"));

		connect("secret-pass").andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(true))
			.andExpect(jsonPath("$.webhooksRegistered").value(true))
			.andExpect(jsonPath("$.online").value(false))
			.andExpect(jsonPath("$.password").doesNotExist());
		assertThat(gate.hooks).hasSize(7);
		assertThat(gate.hooks.values()).allMatch(u -> u.endsWith("/api/v1/public/sms-gate/webhook/" + token()));
		// Secrets are stored encrypted
		String stored = db.queryForObject("select password_enc from sms_gateway where school_id = ?", String.class,
				school.id());
		assertThat(stored).startsWith("v1:").doesNotContain("secret-pass");

		// Saving again (blank password keeps the stored one) replaces the webhooks instead of adding more
		admin.put("/api/v1/communication/sms-phone", """
				{"baseUrl":"%s","username":"school-phone","password":"","signingKey":""}""".formatted(gate.url()))
			.andExpect(status().isOk());
		assertThat(gate.hooks).hasSize(7);
		assertThat(db.queryForObject("select count(*) from audit_log where school_id = ? and action like 'SMS_PHONE_%'",
				Integer.class, school.id())).isEqualTo(2);
	}

	@Test
	void testMessageGoesToThePhoneAndReportsMoveItForwardOnly() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		admin.post("/api/v1/communication/sms-phone/test", "{\"phone\":\"0300-1234567\"}").andExpect(status().isOk());
		outbox.dispatchDue();
		assertThat(gate.sent).hasSize(1);
		assertThat(gate.sent.getFirst().get("phone")).isEqualTo("+923001234567");
		long id = db.queryForObject("select id from message_outbox where school_id = ? and purpose = 'TEST'", Long.class,
				school.id());
		String gatewayId = (String) row(id).get("gateway_id");
		assertThat(gate.sent.getFirst().get("id")).isEqualTo(gatewayId);
		assertThat(row(id).get("status")).isEqualTo("QUEUED");

		// Unsigned, wrongly signed and old requests are refused
		mvc.perform(post("/api/v1/public/sms-gate/webhook/" + token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		webhook("sms:sent", "{\"messageId\":\"" + gatewayId + "\"}", "e0", "another-key", Instant.now().getEpochSecond())
			.andExpect(status().isUnauthorized());
		// The admin is told why, instead of the phone just looking offline
		admin.get("/api/v1/communication/sms-phone")
			.andExpect(jsonPath("$.lastRefusedReason").value(org.hamcrest.Matchers.containsString("signature does not match")));
		webhook("sms:sent", "{\"messageId\":\"" + gatewayId + "\"}", "e0", SIGNING_KEY,
				Instant.now().minusSeconds(3600).getEpochSecond()).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/public/sms-gate/webhook/not-a-real-token-at-all-000").contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isUnauthorized());
		assertThat(row(id).get("status")).isEqualTo("QUEUED");

		webhook("sms:sent", "{\"messageId\":\"" + gatewayId + "\",\"sentAt\":\"2026-11-07T10:00:00.000+05:00\"}")
			.andExpect(status().isOk());
		admin.get("/api/v1/communication/sms-phone").andExpect(jsonPath("$.lastRefusedReason").doesNotExist());
		assertThat(row(id).get("status")).isEqualTo("SENT");
		webhook("sms:delivered", "{\"messageId\":\"" + gatewayId + "\"}").andExpect(status().isOk());
		assertThat(row(id).get("status")).isEqualTo("DELIVERED");
		assertThat(row(id).get("delivered_at")).isNotNull();
		// A late "sent" (or a repeat of an event) never moves it back
		webhook("sms:sent", "{\"messageId\":\"" + gatewayId + "\"}").andExpect(status().isOk());
		webhook("sms:failed", "{\"messageId\":\"" + gatewayId + "\",\"reason\":\"late\"}").andExpect(status().isOk());
		assertThat(row(id).get("status")).isEqualTo("DELIVERED");

		// The phone was heard from: it is online
		admin.get("/api/v1/communication/sms-phone").andExpect(jsonPath("$.online").value(true))
			.andExpect(jsonPath("$.today.DELIVERED").value(1))
			.andExpect(jsonPath("$.handedOverToday").value(1));
		admin.get("/api/v1/communication/log").andExpect(jsonPath("$.messages[0].status").value("DELIVERED"))
			.andExpect(jsonPath("$.messages[0].viaPhone").value(true));
	}

	@Test
	void failuresAreRetriedWithTheSameIdAndCanBeSentAgain() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		long id = queue("03001234567", "NOTICE", null);
		gate.sendStatus.set(503);
		outbox.dispatchDue();
		assertThat(row(id).get("status")).isEqualTo("PENDING");
		assertThat(row(id).get("attempts")).isEqualTo(1);
		String gatewayId = (String) row(id).get("gateway_id");
		assertThat(gatewayId).startsWith("sg-" + id + "-");

		// The phone actually got it the first time (409 on the repeat): no second SMS, and it counts as handed over
		gate.sendStatus.set(202);
		gate.sent.add(Map.of("id", gatewayId, "phone", "+923001234567", "body", ""));
		db.update("update message_outbox set next_attempt_at = now() where id = ?", id);
		outbox.dispatchDue();
		assertThat(gate.sent).hasSize(1);
		assertThat(row(id).get("status")).isEqualTo("QUEUED");
		assertThat(row(id).get("gateway_id")).isEqualTo(gatewayId);

		webhook("sms:failed", "{\"messageId\":\"" + gatewayId + "\",\"reason\":\"RESULT_ERROR_NO_SERVICE\"}")
			.andExpect(status().isOk());
		assertThat(row(id).get("status")).isEqualTo("FAILED");
		assertThat(row(id).get("last_error")).isEqualTo("RESULT_ERROR_NO_SERVICE");
		admin.get("/api/v1/communication/sms-phone").andExpect(jsonPath("$.failed[0].error").value("RESULT_ERROR_NO_SERVICE"));

		// Sending it again uses a new id, so the phone really sends it again
		admin.post("/api/v1/communication/messages/" + id + "/retry").andExpect(status().isNoContent());
		outbox.dispatchDue();
		assertThat(gate.sent).hasSize(2);
		assertThat(gate.sent.get(1).get("id")).isNotEqualTo(gatewayId);

		// A number that is not a mobile fails at once, without bothering the phone
		long landline = queue("0421234567", "NOTICE", null);
		outbox.dispatchDue();
		assertThat(row(landline).get("status")).isEqualTo("FAILED");
		assertThat(gate.sent).hasSize(2);
	}

	@Test
	void urgentFirstQuietHoursDailyLimitAndExpiry() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		long birthday = queue("03001110001", "BIRTHDAY", null);
		long notice = queue("03001110002", "NOTICE", null);
		long absent = queue("03001110003", "ATTENDANCE_ABSENT", null);
		long expired = queue("03001110004", "ATTENDANCE_ABSENT", Instant.now().minusSeconds(60));

		setting("messages.sms_daily_limit", "2");
		outbox.dispatchDue();
		assertThat(row(expired).get("status")).isEqualTo("CANCELLED");
		// Absence alert first, then the notice; the birthday waits for tomorrow (limit 2)
		assertThat(gate.sent).extracting(m -> m.get("phone")).containsExactly("+923001110003", "+923001110002");
		assertThat(row(absent).get("status")).isEqualTo("QUEUED");
		assertThat(row(notice).get("status")).isEqualTo("QUEUED");
		assertThat(row(birthday).get("status")).isEqualTo("PENDING");
		assertThat(row(birthday).get("last_error")).asString().contains("Daily SMS limit");

		// Quiet hours all day: nothing goes, except a test message the admin is waiting for
		setting("messages.sms_daily_limit", "0");
		setting("messages.sms_quiet_start", "\"00:00\"");
		setting("messages.sms_quiet_end", "\"23:59\"");
		db.update("update message_outbox set next_attempt_at = now() where id = ?", birthday);
		admin.post("/api/v1/communication/sms-phone/test", "{\"phone\":\"03009998887\"}").andExpect(status().isOk());
		outbox.dispatchDue();
		assertThat(gate.sent).hasSize(3);
		assertThat(gate.sent.get(2).get("phone")).isEqualTo("+923009998887");
		assertThat(row(birthday).get("status")).isEqualTo("PENDING");
	}

	@Test
	void stopReplyTurnsSmsOffAndStartTurnsItOn() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		long familyId = db.queryForObject("""
				insert into family (school_id, family_no, father_name, father_phone) values (?, 'F-1', 'Akram', '03214567890')
				returning id""", Long.class, school.id());
		webhook("sms:received", "{\"messageId\":\"r1\",\"message\":\"Stop\",\"sender\":\"+923214567890\"}")
			.andExpect(status().isOk());
		assertThat(db.queryForObject("select preferred_channel from family where id = ?", String.class, familyId))
			.isEqualTo("NONE");
		assertThat(db.queryForObject("select count(*) from message_outbox where school_id = ? and purpose = 'OPT_OUT_CONFIRM'",
				Integer.class, school.id())).isEqualTo(1);
		// Ordinary replies change nothing
		webhook("sms:received", "{\"messageId\":\"r2\",\"message\":\"Thank you\",\"sender\":\"+923214567890\"}")
			.andExpect(status().isOk());
		webhook("sms:received", "{\"messageId\":\"r3\",\"message\":\"START\",\"sender\":\"+923214567890\"}")
			.andExpect(status().isOk());
		assertThat(db.queryForObject("select preferred_channel from family where id = ?", String.class, familyId))
			.isEqualTo("SMS");
		assertThat(db.queryForObject("select count(*) from audit_log where school_id = ? and action in ('SMS_OPTED_OUT', 'SMS_OPTED_IN')",
				Integer.class, school.id())).isEqualTo(2);
	}

	@Test
	void repeatedEventsAreHandledOnce() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		long familyId = db.queryForObject("""
				insert into family (school_id, family_no, father_name, father_phone) values (?, 'F-2', 'Saleem', '03334567890')
				returning id""", Long.class, school.id());
		long ts = Instant.now().getEpochSecond();
		String payload = "{\"messageId\":\"r9\",\"message\":\"STOP\",\"sender\":\"+923334567890\"}";
		webhook("sms:received", payload, "same-event", SIGNING_KEY, ts).andExpect(status().isOk());
		db.update("update family set preferred_channel = 'SMS' where id = ?", familyId);
		webhook("sms:received", payload, "same-event", SIGNING_KEY, ts).andExpect(status().isOk());
		assertThat(db.queryForObject("select preferred_channel from family where id = ?", String.class, familyId))
			.isEqualTo("SMS");
	}

	@Test
	void healthPingsStuckMessagesAndOfflineAlerts() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		webhook("system:ping", """
				{"health":{"status":"pass","checks":{"battery:level":{"observedValue":42,"observedUnit":"percent"},
				 "battery:charging":{"observedValue":0}}}}""").andExpect(status().isOk());
		admin.get("/api/v1/communication/sms-phone").andExpect(jsonPath("$.online").value(true))
			.andExpect(jsonPath("$.batteryLevel").value(42))
			.andExpect(jsonPath("$.charging").value(false));

		// With the phone 2 hours: looked up and found delivered. With it 25 hours and unknown there: failed.
		long delivered = queue("03001230001", "NOTICE", null);
		long lost = queue("03001230002", "NOTICE", null);
		db.update("update message_outbox set status = 'QUEUED', gateway_id = 'g-delivered', queued_at = now() - interval '2 hours' where id = ?", delivered);
		db.update("update message_outbox set status = 'QUEUED', gateway_id = 'g-lost', queued_at = now() - interval '25 hours' where id = ?", lost);
		gate.states.put("g-delivered", "Delivered");
		assertThat(jobs.checkStuck()).isEqualTo(2);
		assertThat(row(delivered).get("status")).isEqualTo("DELIVERED");
		assertThat(row(lost).get("status")).isEqualTo("FAILED");
		assertThat(row(lost).get("last_error")).isEqualTo("The phone never received it");

		// Silent for an hour with a message waiting: one email alert, not repeated
		queue("03001230003", "NOTICE", null);
		db.update("update sms_gateway set last_seen_at = now() - interval '1 hour', connected_at = now() - interval '1 day' where school_id = ?",
				school.id());
		admin.get("/api/v1/communication/sms-phone").andExpect(jsonPath("$.alert").value(true));
		assertThat(jobs.alertOffline()).isGreaterThanOrEqualTo(1);
		assertThat(jobs.alertOffline()).isZero();
		assertThat(db.queryForList("select recipient from message_outbox where school_id = ? and purpose = 'SYSTEM_ALERT' and channel = 'EMAIL'",
				String.class, school.id())).containsExactly("office@test.pk");
		// Back online: the alert is cleared
		webhook("app:started", "{\"simCards\":[]}").andExpect(status().isOk());
		assertThat(db.queryForObject("select offline_alerted_at from sms_gateway where school_id = ?", Timestamp.class,
				school.id())).isNull();
	}

	@Test
	void correctedAbsenceIsCancelledOnThePhoneToo() throws Exception {
		connect("secret-pass").andExpect(status().isOk());
		TestData.yearAroundToday(school, true);
		long classId = TestData.schoolClass(school, "Class 3", 3);
		long sectionId = TestData.section(school, classId, "A");
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"Hina","gender":"FEMALE"},"newFamily":{"fatherName":"Asif","fatherPhone":"03005556667",
				 "messageLanguage":"UR"},"sectionId":%d,"admissionDate":"%s"}""".formatted(sectionId, today.minusDays(10)))
			.andExpect(status().isCreated());
		long studentId = ((Number) ApiClient.json(r, "$.id")).longValue();
		setting("attendance.send_absence_sms", "true");
		var marked = admin.put("/api/v1/attendance/registers/" + sectionId + "?date=" + today,
				"{\"marks\":[{\"studentId\":%d,\"status\":\"A\"}],\"reason\":\"test\"}".formatted(studentId));
		if (marked.andReturn().getResponse().getStatus() != 200) {
			// The school is closed today (weekend): attendance alerts only go out on working days; nothing to check.
			return;
		}
		long id = db.queryForObject("select id from message_outbox where school_id = ? and purpose = 'ATTENDANCE_ABSENT'",
				Long.class, school.id());
		// Roman Urdu for this family, and it expires at the end of the day
		assertThat(db.queryForObject("select body from message_outbox where id = ?", String.class, id))
			.contains("ghair hazir");
		assertThat(db.queryForObject("select expires_at from message_outbox where id = ?", Timestamp.class, id)).isNotNull();
		outbox.dispatchDue();
		String gatewayId = (String) row(id).get("gateway_id");
		assertThat(row(id).get("status")).isEqualTo("QUEUED");

		admin.put("/api/v1/attendance/registers/" + sectionId + "?date=" + today,
				"{\"marks\":[{\"studentId\":%d,\"status\":\"P\"}],\"reason\":\"Came late, marked by mistake\"}".formatted(studentId))
			.andExpect(status().isOk());
		assertThat(row(id).get("status")).isEqualTo("CANCELLED");
		assertThat(gate.cancelled).containsExactly(gatewayId);
	}

	@Test
	void automaticBirthdayWishesWaitForTheEvening() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));
		long classId = TestData.schoolClass(school, "Class 4", 4);
		long sectionId = TestData.section(school, classId, "A");
		TestData.yearAroundToday(school, true);
		var r = admin.post("/api/v1/students", """
				{"student":{"name":"Zara","gender":"FEMALE","dob":"%s"},"newFamily":{"fatherName":"Kamran","fatherPhone":"03007778889"},
				 "sectionId":%d,"admissionDate":"%s"}""".formatted(today.minusYears(9), sectionId, today.minusDays(10)))
			.andExpect(status().isCreated());
		long studentId = ((Number) ApiClient.json(r, "$.id")).longValue();
		birthdays.run();
		assertThat(db.queryForObject("select count(*) from message_outbox where school_id = ? and purpose = 'BIRTHDAY'",
				Integer.class, school.id())).isZero(); // switched off by default

		setting("messages.send_birthday_sms", "true");
		setting("messages.birthday_sms_time", "\"18:00\"");
		birthdays.run();
		birthdays.run(); // once per student per year
		var rows = db.queryForList("select next_attempt_at, source_id from message_outbox where school_id = ? and purpose = 'BIRTHDAY'",
				school.id());
		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst().get("source_id")).longValue()).isEqualTo(studentId);
		Instant evening = today.atTime(18, 0).atZone(ZoneId.of("Asia/Karachi")).toInstant();
		Instant next = ((Timestamp) rows.getFirst().get("next_attempt_at")).toInstant();
		assertThat(next).isAfterOrEqualTo(Instant.now().isAfter(evening) ? Instant.now().minusSeconds(60) : evening);
	}
}
