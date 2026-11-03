package com.selectcode.sms.shared.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-memory outbox for local development and tests (profiles local/test). Never active in staging/production. */
public class DevOutbox implements MessageSender {

	public record OutboxEntry(Instant at, OutgoingMessage message) {
	}

	private final List<OutboxEntry> entries = new ArrayList<>();

	@Override
	public synchronized boolean send(OutgoingMessage message) {
		entries.add(0, new OutboxEntry(Instant.now(), message));
		if (entries.size() > 100) {
			entries.removeLast();
		}
		return true;
	}

	@Override
	public boolean supports(OutgoingMessage.Channel channel) {
		return true;
	}

	public synchronized List<OutboxEntry> entries() {
		return List.copyOf(entries);
	}

	public synchronized void clear() {
		entries.clear();
	}
}
