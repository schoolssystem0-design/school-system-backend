package com.selectcode.sms.communication;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The wording of automatic messages (FR 8.6, English only). A school may customise a template; otherwise the built-in
 * wording is used. Placeholders are written {name}; {school} is always available.
 */
@Component
public class MessageTemplates {

	public enum Code {

		FEE_RECEIPT("Fee receipt",
				"{school}: Received {amount} on {date} for family {family}. Receipt {receipt}. {balance} Thank you.",
				List.of("school", "amount", "date", "family", "receipt", "balance")),
		FEE_REMINDER("Fee reminder",
				"{school}: Dear {name}, fees of {amount} are overdue. Please pay at the school office or bank. Ignore if already paid.",
				List.of("school", "name", "amount")),
		ATTENDANCE_ABSENT("Absence",
				"{school}: {name} was absent from school today ({date}). Please contact the school if this is not correct.",
				List.of("school", "name", "date")),
		WELCOME("Welcome to a new student",
				"{school}: Welcome {name} to {class}. We are glad to have your family with us. First day: {date}.",
				List.of("school", "name", "class", "date")),
		BIRTHDAY("Birthday wish", "{school}: Wishing {name} a very happy birthday!", List.of("school", "name"));

		private final String label;

		private final String defaultBody;

		private final List<String> placeholders;

		Code(String label, String defaultBody, List<String> placeholders) {
			this.label = label;
			this.defaultBody = defaultBody;
			this.placeholders = placeholders;
		}

		public String label() {
			return label;
		}

		public String defaultBody() {
			return defaultBody;
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

	/** The school's wording for a template, before placeholders are filled. */
	public String body(Code code) {
		return jdbc.queryForList("select body from message_template where code = ?", String.class, code.name())
			.stream()
			.findFirst()
			.orElse(code.defaultBody());
	}

	/** The finished message: placeholders filled and the school's message signature added. */
	public String render(Code code, Map<String, String> values) {
		return finish(body(code), values);
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
