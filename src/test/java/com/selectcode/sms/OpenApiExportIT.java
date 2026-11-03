package com.selectcode.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Writes the OpenAPI document to {@code target/openapi.json}. The frontend generates its API types from it
 * ({@code npm run gen:api}) and CI fails if the committed types drift.
 */
@AutoConfigureMockMvc
class OpenApiExportIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void exportOpenApiDocument() throws Exception {
		String json = mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(json).contains("/api/v1/auth/login");
		Path target = Path.of("target", "openapi.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, json, StandardCharsets.UTF_8);
	}
}
