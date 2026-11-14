package com.selectcode.sms.shared.events;

import java.time.LocalDate;

/** Student lifecycle events, published in the same transaction (fees will use them from Phase 3). */
public final class StudentEvents {

	private StudentEvents() {
	}

	/** A new student was admitted (FR 4.10: admission fee and first charges are proposed in Phase 3). */
	public record Admitted(long studentId, long yearId, long classId, LocalDate admissionDate) {
	}

	/** A student left (FR 5.6). */
	public record Withdrawn(long studentId, LocalDate leavingDate, String reason) {
	}
}
