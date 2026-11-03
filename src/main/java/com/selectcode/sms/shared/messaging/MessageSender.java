package com.selectcode.sms.shared.messaging;

/**
 * Sends email/SMS. Phase 1 uses it for password resets, account setup links and step-up codes; the full messaging
 * module (templates, outbox, delivery log, SMS/WhatsApp providers) arrives in Phase 7.
 */
public interface MessageSender {

	/** @return true if the message was handed to a provider */
	boolean send(OutgoingMessage message);

	boolean supports(OutgoingMessage.Channel channel);
}
