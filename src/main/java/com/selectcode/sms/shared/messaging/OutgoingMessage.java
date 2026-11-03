package com.selectcode.sms.shared.messaging;

/** A message to one recipient. Bodies may contain one-time codes: never log them. */
public record OutgoingMessage(Channel channel, String recipient, String subject, String body, String sender) {

	public OutgoingMessage(Channel channel, String recipient, String subject, String body) {
		this(channel, recipient, subject, body, null);
	}

	public enum Channel {
		EMAIL, SMS
	}

	public static OutgoingMessage email(String to, String subject, String body) {
		return new OutgoingMessage(Channel.EMAIL, to, subject, body);
	}
}
