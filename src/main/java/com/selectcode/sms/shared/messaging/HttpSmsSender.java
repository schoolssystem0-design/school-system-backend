package com.selectcode.sms.shared.messaging;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SMS through one bulk-SMS gateway with an HTTP API (DECISIONS D-06). The gateway is chosen when the account is opened,
 * so the request is configuration: a URL with {to}, {text} and {mask} (URL-encoded when filled). A response is accepted
 * when its status is 2xx and its body does not contain the configured failure text.
 */
class HttpSmsSender {

	private final String urlTemplate;

	private final String failureText;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	HttpSmsSender(String urlTemplate, String failureText) {
		this.urlTemplate = urlTemplate;
		this.failureText = failureText == null || failureText.isBlank() ? null : failureText;
	}

	boolean send(OutgoingMessage message) {
		String url = urlTemplate.replace("{to}", encode(message.recipient()))
			.replace("{text}", encode(message.body()))
			.replace("{mask}", encode(message.sender() == null ? "" : message.sender()));
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			boolean ok = response.statusCode() / 100 == 2;
			return ok && (failureText == null || !response.body().contains(failureText));
		}
		catch (IOException e) {
			return false;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
