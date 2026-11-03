package com.selectcode.sms.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint any signed-in user may call without a specific permission (own profile, change password,
 * academic year switcher). Authentication itself is still required by SecurityConfig.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AnyAuthenticated {
}
