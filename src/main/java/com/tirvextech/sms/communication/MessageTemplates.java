package com.tirvextech.sms.communication;

import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The wording of automatic messages (FR 8.6), in English and Roman Urdu. Each family chooses its language; a school may
 * customise any template, otherwise the built-in wording is used. Placeholders are written {name}; {school} is always
 * available. Roman Urdu stays in plain letters, so a message still fits one SMS (Urdu script would cost 2–3 times more).
 */
@Component
public class MessageTemplates {

	public static final String ENGLISH = "EN";

	public static final String ROMAN_URDU = "UR";

	public static final List<String> LANGUAGES = List.of(ENGLISH, ROMAN_URDU);

	public enum Code {

		FEE_RECEIPT("Fee receipt",
				"{school}: Received {amount} on {date} for family {family}. Receipt {receipt}. {balance} Thank you.",
				"{school}: Family {family} se {amount} wusool hue ({date}). Raseed {receipt}. {balance} Shukriya.",
				"This is {school}. We received fee payment of {amount} on {date}. Receipt number {receipt}. {balance} Thank you.",
				"{school} نے {date} کو {amount} کی فیس وصول کی۔ رسید نمبر {receipt}۔ {balance} شکریہ۔",
				List.of("school", "amount", "date", "family", "receipt", "balance")),
		FEE_REMINDER("Fee reminder",
				"{school}: Dear {name}, fees of {amount} are overdue. Please pay at the school office or bank. Ignore if already paid.",
				"{school}: Mohtaram {name}, {amount} fees baqaya hai. Barah-e-karam school office ya bank mein jama karwayen. Ada kar di ho to nazar-andaz karen.",
				"Assalam o alaikum. This is {school}. Dear {name}, school fees of {amount} are due. "
						+ "Please pay at the school office or bank. Ignore this if you have already paid.",
				"السلام علیکم۔ {school} کی طرف سے اطلاع ہے کہ محترم {name}، فیس کی رقم {amount} بقایا ہے۔ "
						+ "براہ کرم سکول آفس یا بینک میں جمع کروائیں۔ اگر ادا کر چکے ہیں تو نظرانداز کریں۔",
				List.of("school", "name", "amount")),
		ATTENDANCE_ABSENT("Absence",
				"{school}: {name} was absent from school today ({date}). Please contact the school if this is not correct.",
				"{school}: {name} aaj ({date}) school se ghair hazir hai. Agar yeh ghalat hai to school se rabta karen.",
				"Assalam o alaikum. This is {school}. Your child {name} was absent from school today, {date}. "
						+ "Please contact the school if this is incorrect.",
				"السلام علیکم۔ {school} کی طرف سے اطلاع ہے کہ آپ کا بچہ {name} آج {date} کو سکول میں حاضر نہیں تھا۔ "
						+ "اگر یہ غلط ہے تو براہ کرم سکول سے رابطہ کریں۔",
				List.of("school", "name", "date")),
		WELCOME("Welcome to a new student",
				"{school}: Welcome {name} to {class}. We are glad to have your family with us. First day: {date}.",
				"{school}: {name} ko {class} mein khush amdeed. Hamein aap ke khandan ka hissa ban kar khushi hai. Pehla din: {date}.",
				List.of("school", "name", "class", "date")),
		BIRTHDAY("Birthday wish", "{school}: Wishing {name} a very happy birthday!",
				"{school}: {name} ko saalgirah bohat bohat mubarak!", List.of("school", "name")),
		TEST_MARKS("Test marks", "{school}: {name} - {subject} test ({date}): {marks}.",
				"{school}: {name} - {subject} test ({date}): {marks}.", List.of("school", "name", "subject", "date", "marks")),
		SCHOOL_CLOSED("School closed",
				"{school}: School will remain closed on {date}. {detail}",
				"{school}: {date} ko school band rahega. {detail}",
				"This is {school}. The school will remain closed on {date}. {detail}",
				"{school} کی اطلاع ہے کہ {date} کو سکول بند رہے گا۔ {detail}",
				List.of("school", "date", "detail"));

		private final String label;

		private final String defaultBody;

		private final String defaultUrdu;

		private final String defaultVoiceEn;

		private final String defaultVoiceUr;

		private final List<String> placeholders;

		Code(String label, String defaultBody, String defaultUrdu, List<String> placeholders) {
			this(label, defaultBody, defaultUrdu, defaultBody, defaultUrdu, placeholders);
		}

		Code(String label, String defaultBody, String defaultUrdu, String defaultVoiceEn, String defaultVoiceUr,
				List<String> placeholders) {
			this.label = label;
			this.defaultBody = defaultBody;
			this.defaultUrdu = defaultUrdu;
			this.defaultVoiceEn = defaultVoiceEn;
			this.defaultVoiceUr = defaultVoiceUr;
			this.placeholders = placeholders;
		}

		public String label() {
			return label;
		}

		public String defaultBody() {
			return defaultBody;
		}

		public String defaultBody(String language) {
			return ROMAN_URDU.equals(language) ? defaultUrdu : defaultBody;
		}

		public List<String> placeholders() {
			return placeholders;
		}
	}

	private final JdbcTemplate jdbc;

	private final SettingsService settings;

	MessageTemplates(JdbcTemplate jdbc, SettingsService settings) {
		this.jdbc = jdbc;
		this.settings = settings;
	}

	/** "EN" or "UR"; anything else is treated as English. */
	public static String language(String value) {
		return ROMAN_URDU.equals(value) ? ROMAN_URDU : ENGLISH;
	}

	/** The language a family chose for messages. */
	public String familyLanguage(Long familyId) {
		if (familyId == null) {
			return ENGLISH;
		}
		return language(jdbc.queryForList("select message_language from family where id = ?", String.class, familyId)
			.stream()
			.findFirst()
			.orElse(ENGLISH));
	}

	/** The school's English wording for a template, before placeholders are filled. */
	public String body(Code code) {
		return body(code, ENGLISH);
	}

	/** The school's wording for a template in a language, before placeholders are filled. */
	public String body(Code code, String language) {
		String lang = language(language);
		return jdbc.queryForList("select body from message_template where code = ? and language = ?", String.class,
				code.name(), lang)
			.stream()
			.findFirst()
			.orElse(code.defaultBody(lang));
	}

	/** The finished English message: placeholders filled and the school's message signature added. */
	public String render(Code code, Map<String, String> values) {
		return finish(body(code), values);
	}

	/** The finished message in a family's language. */
	public String render(Code code, Map<String, String> values, String language) {
		return finish(body(code, language), values);
	}

	/** Spoken script for the school phone (Urdu script or plain English). */
	public String voiceScript(Code code, Map<String, String> values, String language) {
		String lang = language(language);
		String school = jdbc.queryForObject("select name from school where id = ?", String.class,
				TenantContext.requireSchoolId());
		String pattern = ROMAN_URDU.equals(lang) ? code.defaultVoiceUr : code.defaultVoiceEn;
		String result = pattern.replace("{school}", school);
		for (var e : values.entrySet()) {
			result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
		}
		return result;
	}

	/** Fills placeholders in any text and adds the school's message signature. */
	public String finish(String text, Map<String, String> values) {
		String school = jdbc.queryForObject("select name from school where id = ?", String.class,
				TenantContext.requireSchoolId());
		String result = text.replace("{school}", school);
		for (var e : values.entrySet()) {
			result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
		}
		String signature = settings.text(SettingKey.MESSAGE_SIGNATURE);
		return signature == null || signature.isBlank() ? result : result + " - " + signature.trim();
	}
}
