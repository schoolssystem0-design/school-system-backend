package com.selectcode.sms.shared.security;

import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class StepUpConfig implements WebMvcConfigurer {

	private final Clock clock;

	StepUpConfig(Clock clock) {
		this.clock = clock;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StepUpInterceptor(clock));
	}
}
