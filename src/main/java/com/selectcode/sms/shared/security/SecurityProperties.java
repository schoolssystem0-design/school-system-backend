package com.selectcode.sms.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins browser origins allowed to call the API with credentials (the Next.js app domain only)
 * @param cookieDomain session cookie domain, e.g. {@code .example.com}; blank = host-only (local development)
 * @param cookieSecure whether cookies are marked Secure (true everywhere except local http)
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(List<String> allowedOrigins, String cookieDomain, boolean cookieSecure) {

	public SecurityProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
	}
}
