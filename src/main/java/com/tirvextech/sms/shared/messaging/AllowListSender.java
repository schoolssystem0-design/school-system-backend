package com.selectcode.sms.shared.messaging;

import java.util.Set;

/** Outside production, messages reach only listed recipients (QA-COM-004); others are refused and end as FAILED. */
class AllowListSender implements MessageSender {

	private final MessageSender delegate;

	private final Set<String> allowed;

	AllowListSender(MessageSender delegate, Set<String> allowed) {
		this.delegate = delegate;
		this.allowed = allowed;
	}

	@Override
	public boolean send(OutgoingMessage message) {
		return allowed.contains(message.recipient()) && delegate.send(message);
	}

	@Override
	public boolean supports(OutgoingMessage.Channel channel) {
		return delegate.supports(channel);
	}
}
