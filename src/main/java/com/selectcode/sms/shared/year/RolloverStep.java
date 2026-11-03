package com.selectcode.sms.shared.year;

/**
 * One item that can be carried into a new academic year (FR 1.3): class subjects, teaching assignments, fee
 * structure (Phase 3), grading rules (Phase 6). Modules provide steps; the school module runs the chosen ones.
 */
public interface RolloverStep {

	String key();

	String label();

	/** Copies from one year to the other; returns how many records were copied. Runs in the caller's transaction. */
	int copy(AcademicYearLookup.YearRef from, AcademicYearLookup.YearRef to);
}
