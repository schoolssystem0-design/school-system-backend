package com.tirvextech.sms.shared.messaging.smsgate;

/** Port and payload contract between Tirvex School System and the voice companion on the school phone. */
public final class TirvexVoice {

	/** SMS Gate data-message port reserved for Tirvex voice calls (companion app v1+). */
	public static final int DATA_PORT = 53740;

	private TirvexVoice() {
	}
}