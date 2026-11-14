package com.selectcode.sms.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.sms.shared.security.SecurityConfig;
import com.selectcode.sms.support.IntegrationTest;
import com.selectcode.sms.support.TestData;
import com.selectcode.sms.support.TestData.SeededSchool;
import com.selectcode.sms.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 0 spike B (server side): school sign-in with session cookie + CSRF, and QA-SEC basics. */
@AutoConfigureMockMvc
class SignInFlowIT extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	SeededSchool schoolA;

	SeededSchool schoolB;

	/** A browser-like cookie jar: CSRF cookie + session cookie. */
	Cookie csrfCookie;

	Cookie sessionCookie;

	/** The token from GET /auth/csrf, sent back as X-XSRF-TOKEN (as the Next.js app does). */
	String csrfToken;

	@BeforeEach
	void seed() throws Exception {
		schoolA = TestData.school("A");
		schoolB = TestData.school("B");
		TestData.user(schoolA, "admin", "password-a");
		TestData.user(schoolB, "admin", "password-b");
		MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
		csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(csrfCookie).as("CSRF cookie").isNotNull();
		assertThat(csrfCookie.isHttpOnly()).as("CSRF cookie is HttpOnly").isTrue();
		csrfToken = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		sessionCookie = null;
	}

	@Test
	void signInThenMeThenSignOut() throws Exception {
		MvcResult login = login(schoolA.code(), "admin", "password-a").andExpect(status().isOk())
			.andExpect(jsonPath("$.school.code").value(schoolA.code()))
			.andExpect(jsonPath("$.loginName").value("admin"))
			.andReturn();
		sessionCookie = login.getResponse().getCookie(SecurityConfig.SESSION_COOKIE);
		assertThat(sessionCookie).isNotNull();
		assertThat(sessionCookie.isHttpOnly()).isTrue();

		mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.school.id").value(schoolA.id()));

		mvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie, csrfCookie).header("X-XSRF-TOKEN", csrfToken))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void sameUsernameWorksOnlyWithItsOwnSchoolCode() throws Exception {
		login(schoolA.code(), "admin", "password-a").andExpect(status().isOk());
		login(schoolB.code(), "admin", "password-b").andExpect(status().isOk());
		login(schoolA.code(), "admin", "password-b").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void schoolCodeIsCaseInsensitive() throws Exception {
		login(schoolA.code().toLowerCase(), "Admin", "password-a").andExpect(status().isOk());
	}

	@Test
	void wrongPasswordIsRejectedAndRecorded() throws Exception {
		login(schoolA.code(), "admin", "wrong").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.requestId").isNotEmpty());
		Long invalid = TestDatabase.superuser()
			.queryForObject("select count(*) from login_event where school_id = ? and result = 'INVALID'", Long.class,
					schoolA.id());
		assertThat(invalid).isEqualTo(1);
	}

	@Test
	void fiveFailuresLockTheAccount() throws Exception {
		// QA-SEC-007
		for (int i = 0; i < 5; i++) {
			login(schoolA.code(), "admin", "wrong").andExpect(status().isUnauthorized());
		}
		login(schoolA.code(), "admin", "password-a").andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		// Other school's same username is unaffected.
		login(schoolB.code(), "admin", "password-b").andExpect(status().isOk());
	}

	@Test
	void sameUserCanSignInFromSeveralDevicesAtOnce() throws Exception {
		// QA-SEC-021: sign-in bookkeeping must not race on the user's optimistic-lock version.
		Long versionBefore = TestDatabase.superuser()
			.queryForObject("select version from app_user where school_id = ? and username = 'admin'", Long.class,
					schoolA.id());
		var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
		try {
			var calls = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 6; i++) {
				calls.add(pool.submit(() -> login(schoolA.code(), "admin", "password-a").andReturn().getResponse().getStatus()));
			}
			for (var call : calls) {
				assertThat(call.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdown();
		}
		Long versionAfter = TestDatabase.superuser()
			.queryForObject("select version from app_user where school_id = ? and username = 'admin'", Long.class,
					schoolA.id());
		assertThat(versionAfter).as("signing in does not count as editing the user").isEqualTo(versionBefore);
	}

	@Test
	void unknownSchoolLooksLikeBadCredentials() throws Exception {
		login("NOPE-SCHOOL", "admin", "password-a").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void suspendedSchoolCannotSignIn() throws Exception {
		TestData.suspend(schoolB);
		login(schoolB.code(), "admin", "password-b").andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("SCHOOL_SUSPENDED"));
	}

	@Test
	void loginWithoutCsrfTokenIsForbidden() throws Exception {
		// QA-SEC-017
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(body(schoolA.code(), "admin", "password-a")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anonymousRequestGetsProblemJson() throws Exception {
		mvc.perform(get("/api/v1/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void blankFieldsAreValidationErrors() throws Exception {
		login("", "", "").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(3));
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
	}

	private ResultActions login(String schoolCode, String username, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.cookie(csrfCookie)
			.header("X-XSRF-TOKEN", csrfToken)
			.content(body(schoolCode, username, password)));
	}

	private static String body(String schoolCode, String username, String password) {
		return """
				{"schoolCode":"%s","username":"%s","password":"%s"}""".formatted(schoolCode, username, password);
	}
}
