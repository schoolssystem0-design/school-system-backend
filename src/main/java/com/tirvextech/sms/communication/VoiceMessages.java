package com.tirvextech.sms.communication;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** JSON handed to the school phone for a spoken Urdu alert (Tirvex voice companion on SMS Gate data port). */
@Component
public class VoiceMessages {

	public static final int PAYLOAD_VERSION = 1;

	private final JsonMapper json;

	VoiceMessages(JsonMapper json) {
		this.json = json;
	}

	public String payload(MessageTemplates.Code template, String script) {
		return json.writeValueAsString(
				java.util.Map.of("v", PAYLOAD_VERSION, "template", template.name(), "script", script));
	}
}