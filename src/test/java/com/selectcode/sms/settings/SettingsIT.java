package com.selectcode.sms.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.messaging.DevOutbox;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** School rules (QA-SET-010..013). */
@AutoConfigureMockMvc
class SettingsIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DevOutbox outbox;

	@Autowired
	SettingsService settings;

	SeededSchool school;

	ApiClient admin;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("RUL");
		TestData.admin(school, "admin", "admin-password-1");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	@Test
	void changeNeedsStepUpAndReasonAndKeepsHistory() throws Exception {
		String key = SettingKey.STUDENT_LATE_TIME_1.key();
		admin.get("/api/v1/settings")
			.andExpect(jsonPath("$[?(@.key=='" + key + "')].value").value("08:00"))
			.andExpect(jsonPath("$[?(@.key=='" + key + "')].isDefault").value(true));

		admin.put("/api/v1/settings/" + key, "{\"value\":\"08:15\",\"reason\":\"Winter timing\"}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
		admin.stepUp(outbox);
		admin.put("/api/v1/settings/" + key, "{\"value\":\"08:15\",\"reason\":\"\"}")
			.andExpect(status().isUnprocessableContent());
		admin.put("/api/v1/settings/" + key, "{\"value\":\"8:15 am\",\"reason\":\"Winter timing\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SETTING_INVALID"));
		admin.put("/api/v1/settings/" + key, "{\"value\":\"08:15\",\"reason\":\"Winter timing\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.value").value("08:15"))
			.andExpect(jsonPath("$.changedBy").value("User admin"));
		admin.get("/api/v1/settings/" + key + "/history")
			.andExpect(jsonPath("$[0].value").value("08:15"))
			.andExpect(jsonPath("$[0].reason").value("Winter timing"));

		// FR 12.3: modules read the same value; other schools keep the default (QA-TEN-012).
		LocalTime late = TenantContext.callAsSchool(school.id(), () -> settings.time(SettingKey.STUDENT_LATE_TIME_1));
		assertThat(late).isEqualTo(LocalTime.of(8, 15));
		var other = TestData.school("RUL2");
		assertThat(TenantContext.callAsSchool(other.id(), () -> settings.time(SettingKey.STUDENT_LATE_TIME_1)))
			.isEqualTo(LocalTime.of(8, 0));
	}

	@Test
	void viewOnlyUsersCannotChangeRules() throws Exception {
		// QA-SET-012: the accountant can view the late-fee rule but not change it.
		TestData.userWithPermissions(school, "accountant", "accounts-pass1", Permissions.SETTINGS_VIEW);
		ApiClient accountant = new ApiClient(mvc);
		accountant.login(school.code(), "accountant", "accounts-pass1").andExpect(status().isOk());
		accountant.get("/api/v1/settings").andExpect(status().isOk());
		accountant.put("/api/v1/settings/" + SettingKey.LATE_FEE_AMOUNT.key(), "{\"value\":\"500\",\"reason\":\"x\"}")
			.andExpect(status().isForbidden());
	}

	@Test
	void typedValidation() {
		assertThat(SettingsService.validate(SettingKey.LATE_FEE_AMOUNT, "200")).isEqualTo("200.00");
		assertThat(SettingsService.validate(SettingKey.SHOW_CLASS_POSITIONS, "true")).isEqualTo(true);
		assertThat(SettingsService.validate(SettingKey.REPEATED_ABSENCE_DAYS, 5)).isEqualTo(5);
		assertThat(SettingsService.validate(SettingKey.RECEIPT_STYLE, "A4")).isEqualTo("A4");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> SettingsService.validate(SettingKey.RECEIPT_STYLE, "Letter"))
			.hasMessageContaining("Choose one of");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> SettingsService.validate(SettingKey.LATE_FEE_AMOUNT, "12.345"))
			.hasMessageContaining("2 decimals");
	}
}
