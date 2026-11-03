package com.selectcode.sms.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The action needs a recent second check (FR 2.6, DECISIONS D-10): a one-time code sent to the user, valid for
 * {@link StepUp#VALIDITY}. The super admin is exempt (TOTP at sign-in). Returns 403 {@code STEP_UP_REQUIRED}
 * otherwise.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequiresStepUp {
}
