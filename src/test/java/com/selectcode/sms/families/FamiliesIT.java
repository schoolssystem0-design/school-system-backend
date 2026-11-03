package com.selectcode.sms.families;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.support.ApiClient;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Family records (QA-FAM). */
@AutoConfigureMockMvc
class FamiliesIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool school;

	ApiClient admin;

	@BeforeEach
	void seed() throws Exception {
		school = TestData.school("FAM");
		TestData.admin(school, "admin", "admin-password-1");
		admin = new ApiClient(mvc);
		admin.login(school.code(), "admin", "admin-password-1").andExpect(status().isOk());
	}

	long create(String body) throws Exception {
		return ((Number) ApiClient.json(admin.post("/api/v1/families", body).andExpect(status().isCreated()), "$.id"))
			.longValue();
	}

	@Test
	void numbersAndContactsAreNormalised() throws Exception {
		long id = create("""
				{"fatherName":"Tariq Mehmood","fatherPhone":"+92 300 1234567","fatherCnic":"3520212345671",
				 "motherPhone":"0321-7654321"}""");
		admin.get("/api/v1/families/" + id)
			.andExpect(jsonPath("$.familyNo").value("F-0001"))
			.andExpect(jsonPath("$.details.fatherPhone").value("03001234567"))
			.andExpect(jsonPath("$.details.fatherCnic").value("35202-1234567-1"));
		admin.post("/api/v1/families", "{\"fatherName\":\"X\",\"fatherCnic\":\"123\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CNIC_INVALID"));
		admin.post("/api/v1/families", "{\"fatherPhone\":\"03001234567\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
		// FR 8.2: find by any phone format or CNIC
		admin.get("/api/v1/families?q=0300-1234567").andExpect(jsonPath("$.items[0].id").value(id));
		admin.get("/api/v1/families?q=35202-1234567-1").andExpect(jsonPath("$.items[0].id").value(id));
		admin.get("/api/v1/families?q=tariq").andExpect(jsonPath("$.items[0].familyNo").value("F-0001"));
	}

	@Test
	void dataQualityReport() throws Exception {
		// QA-FAM-004
		create("{\"fatherName\":\"No Phone\"}");
		admin.get("/api/v1/families/data-issues")
			.andExpect(jsonPath("$[*].issue").value(hasItem("No phone number")))
			.andExpect(jsonPath("$[*].issue").value(hasItem("Father CNIC missing")))
			.andExpect(jsonPath("$[*].issue").value(hasItem("No children linked")));
	}

	@Test
	void privateContactsAreHiddenAndPreservedOnEdit() throws Exception {
		// FR 5.11 / 8.4: an editor who cannot see phones must not wipe them.
		long id = create("{\"fatherName\":\"Asif\",\"fatherPhone\":\"0300-1112223\",\"address\":\"Lahore\"}");
		TestData.userWithPermissions(school, "clerk", "clerk-password-1", Permissions.FAMILIES_VIEW,
				Permissions.FAMILIES_MANAGE);
		ApiClient clerk = new ApiClient(mvc);
		clerk.login(school.code(), "clerk", "clerk-password-1").andExpect(status().isOk());
		var family = clerk.get("/api/v1/families/" + id)
			.andExpect(jsonPath("$.contactsHidden").value(true))
			.andExpect(jsonPath("$.details.fatherPhone").doesNotExist());
		long version = ((Number) ApiClient.json(family, "$.version")).longValue();
		clerk.patch("/api/v1/families/" + id, "{\"fatherName\":\"Asif Iqbal\",\"version\":%d}".formatted(version))
			.andExpect(status().isOk());
		assertThat(TestDatabase.superuser().queryForObject("select father_phone from family where id = ?", String.class, id))
			.isEqualTo("03001112223");
		assertThat(TestDatabase.superuser().queryForObject("select father_name from family where id = ?", String.class, id))
			.isEqualTo("Asif Iqbal");
	}

	@Test
	void bulkPhoneUpdate() throws Exception {
		long a = create("{\"fatherName\":\"A\"}");
		long b = create("{\"fatherName\":\"B\"}");
		admin.put("/api/v1/families/bulk", """
				{"field":"FATHER_PHONE","values":[{"familyId":%d,"value":"0300 1111111"},{"familyId":%d,"value":"03002222222"}]}"""
			.formatted(a, b)).andExpect(jsonPath("$.updated").value(2));
		admin.put("/api/v1/families/bulk", "{\"field\":\"FAMILY_NO\",\"values\":[]}")
			.andExpect(status().isUnprocessableContent());
	}
}
