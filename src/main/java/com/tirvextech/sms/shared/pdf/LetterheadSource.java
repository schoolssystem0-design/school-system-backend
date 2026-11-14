package com.selectcode.sms.shared.pdf;

/** Loads the current school's letterhead (implemented by the school module). */
public interface LetterheadSource {

	Letterhead current();
}
