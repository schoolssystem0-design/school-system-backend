package com.tirvextech.sms.shared.verify;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tirvextech.sms.support.ApiClient;
import com.tirvextech.sms.support.IntegrationTest;
import com.tirvextech.sms.support.TestData;
import com.tirvextech.sms.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DocumentVerificationIT extends IntegrationTest {

	private static final String TOKEN = "test-verify-token-012345";

	@Autowired
	MockMvc mvc;

	@Test
	void publicVerifyShowsGenuineMessage() throws Exception {
		var school = TestData.school("QR1");
		TestDatabase.superuser().update("""
				insert into school_profile (school_id, name) values (?, 'Green Valley School')
				on conflict (school_id) do update set name = excluded.name""", school.id());
		TestDatabase.superuser().update("""
				insert into document_verification (token, school_id, doc_kind, ref_type, ref_id, document_title, subject_line, issued_on)
				values (?, ?, 'CERTIFICATE', 'certificate_issue', 1, 'School leaving certificate', 'Ahmad Khan (GR 101)', current_date)""",
				TOKEN, school.id());
		new ApiClient(mvc).get("/api/v1/public/verify/" + TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.genuine").value(true))
			.andExpect(jsonPath("$.schoolName").value("Green Valley School"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Genuine: issued by Green Valley School")));
		new ApiClient(mvc).get("/api/v1/public/verify/unknown-token-abcdefghij").andExpect(status().isOk())
			.andExpect(jsonPath("$.genuine").value(false));
	}
}