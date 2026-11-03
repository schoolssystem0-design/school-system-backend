package com.selectcode.sms.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that needs no sign-in (it must also be listed as permitAll in SecurityConfig). Every controller
 * method must carry {@code @PreAuthorize}, {@link AnyAuthenticated} or this annotation (ArchitectureTest).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {
}
