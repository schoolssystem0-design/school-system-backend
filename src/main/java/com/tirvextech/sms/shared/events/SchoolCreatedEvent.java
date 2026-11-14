package com.selectcode.sms.shared.events;

/**
 * Published by the platform inside the transaction that creates a school (tenant = the new school). Modules listen
 * with {@code @EventListener} to seed their defaults (e.g. enquiry statuses) in the same transaction.
 */
public record SchoolCreatedEvent(long schoolId, String code, String name) {
}
