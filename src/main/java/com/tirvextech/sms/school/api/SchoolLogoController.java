package com.tirvextech.sms.school.api;

import com.tirvextech.sms.school.app.PublicSchoolLogo;
import com.tirvextech.sms.shared.security.PublicEndpoint;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A school's logo by school code, for the sign-in page (before anyone is signed in). */
@RestController
class SchoolLogoController {

	private final PublicSchoolLogo logos;

	SchoolLogoController(PublicSchoolLogo logos) {
		this.logos = logos;
	}

	@PublicEndpoint
	@GetMapping("/api/v1/public/schools/{code}/logo")
	ResponseEntity<byte[]> logo(@PathVariable String code) {
		return logos.find(code)
			.map(l -> ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
				.contentType(MediaType.parseMediaType(l.contentType()))
				.body(l.content()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
