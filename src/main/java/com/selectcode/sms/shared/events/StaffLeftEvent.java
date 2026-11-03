package com.selectcode.sms.shared.events;

import java.time.LocalDate;

/**
 * A staff member left (FR 6.7). Published in the leaving transaction; academics closes their teaching assignments
 * and removes them from the timetable from the leaving date.
 */
public record StaffLeftEvent(long staffId, LocalDate leavingDate) {
}
