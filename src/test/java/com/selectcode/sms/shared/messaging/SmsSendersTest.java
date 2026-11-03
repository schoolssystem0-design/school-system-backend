package com.selectcode.sms.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The SMS gateway adapter against a local stand-in, and the staging allow-list (QA-COM-003, QA-COM-004). */
class SmsSendersTest {

	private static OutgoingMessage sms(String to, String text) {
		return new OutgoingMessage(OutgoingMessage.Channel.SMS, to, null, text, "NNS SCHOOL");
	}

	@Test
	void gatewayRequestAndResponses() throws Exception {
		List<String> queries = new ArrayList<>();
		int[] status = { 200 };
		String[] reply = { "OK:1234" };
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/send", exchange -> {
			queries.add(exchange.getRequestURI().getRawQuery());
			byte[] body = reply[0].getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status[0], body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			HttpSmsSender sender = new HttpSmsSender(
					"http://127.0.0.1:" + port + "/send?to={to}&mask={mask}&text={text}", "ERROR");
			assertThat(sender.send(sms("03001234567", "Fees & dues: Rs. 500"))).isTrue();
			assertThat(queries.getFirst()).isEqualTo("to=03001234567&mask=NNS+SCHOOL&text=Fees+%26+dues%3A+Rs.+500");
			// The gateway says no in its body, or with a status
			reply[0] = "ERROR: no credit";
			assertThat(sender.send(sms("03001234567", "Hi"))).isFalse();
			reply[0] = "OK";
			status[0] = 500;
			assertThat(sender.send(sms("03001234567", "Hi"))).isFalse();
			// The gateway is down: not sent, and the outbox retries
			server.stop(0);
			assertThat(sender.send(sms("03001234567", "Hi"))).isFalse();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void stagingSendsOnlyToTheAllowList() {
		List<String> sent = new ArrayList<>();
		MessageSender real = new MessageSender() {
			@Override
			public boolean send(OutgoingMessage message) {
				sent.add(message.recipient());
				return true;
			}

			@Override
			public boolean supports(OutgoingMessage.Channel channel) {
				return true;
			}
		};
		AllowListSender sender = new AllowListSender(real, Set.of("03001111111"));
		assertThat(sender.send(sms("03001111111", "Hi"))).isTrue();
		assertThat(sender.send(sms("03009999999", "Hi"))).isFalse();
		assertThat(sent).containsExactly("03001111111");
	}
}
