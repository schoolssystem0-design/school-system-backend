package com.tirvextech.sms.communication;

import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.messaging.MessageOutbox;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Queues family alerts as SMS or as a short Urdu voice call on the school phone, depending on the family's channel and
 * language (parents on voice, or Roman Urdu families when the school enables voice for Urdu).
 */
@Component
public class FamilyOutbox {

	private final MessageOutbox outbox;

	private final MessageTemplates templates;

	private final VoiceMessages voice;

	private final SettingsService settings;

	FamilyOutbox(MessageOutbox outbox, MessageTemplates templates, VoiceMessages voice, SettingsService settings) {
		this.outbox = outbox;
		this.templates = templates;
		this.voice = voice;
		this.settings = settings;
	}

	public static boolean channelOpen(String preferredChannel) {
		return preferredChannel != null && !"NONE".equals(preferredChannel);
	}

	public boolean useVoice(String preferredChannel, String messageLanguage) {
		if ("VOICE".equals(preferredChannel)) {
			return true;
		}
		if (!settings.bool(SettingKey.VOICE_FOR_URDU_FAMILIES)) {
			return false;
		}
		return !"NONE".equals(preferredChannel) && MessageTemplates.ROMAN_URDU.equals(MessageTemplates.language(messageLanguage));
	}

	/**
	 * @return true if queued (false = already queued under this key)
	 */
	public boolean queueTemplated(String phone, String preferredChannel, String messageLanguage, MessageTemplates.Code code,
			Map<String, String> values, String purpose, String sourceType, Long sourceId, String idempotencyKey,
			Instant notBefore, Instant expiresAt) {
		if (!channelOpen(preferredChannel)) {
			return false;
		}
		if (useVoice(preferredChannel, messageLanguage)) {
			return outbox.queueVoice(phone,
					voice.payload(code, templates.voiceScript(code, values, MessageTemplates.language(messageLanguage))),
					purpose, sourceType, sourceId, idempotencyKey, notBefore, expiresAt);
		}
		return outbox.queueSms(phone, templates.render(code, values, messageLanguage), purpose, sourceType, sourceId,
				idempotencyKey, notBefore, expiresAt);
	}

	public boolean queueTemplated(String phone, String preferredChannel, String messageLanguage, MessageTemplates.Code code,
			Map<String, String> values, String purpose, String sourceType, Long sourceId, String idempotencyKey) {
		return queueTemplated(phone, preferredChannel, messageLanguage, code, values, purpose, sourceType, sourceId,
				idempotencyKey, null, null);
	}

	/** Free-text broadcasts (not templated voice). Voice families are skipped. */
	public boolean queueSmsText(String phone, String preferredChannel, String body, String purpose, String sourceType,
			Long sourceId, String idempotencyKey) {
		if (!channelOpen(preferredChannel) || "VOICE".equals(preferredChannel)) {
			return false;
		}
		return outbox.queueSms(phone, body, purpose, sourceType, sourceId, idempotencyKey);
	}
}