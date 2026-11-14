package com.selectcode.sms.shared.usage;

import java.util.List;

/**
 * "Where used" check before deleting master data (FR 9.7). Each module that references classes, sections or subjects
 * contributes its counts (students, attendance, marks, fees…), so used items can only be deactivated.
 */
public interface UsageContributor {

	enum Target {
		CLASS, SECTION, SUBJECT
	}

	/** e.g. ("Sections", 2), ("Marks in exams", 120). */
	record Usage(String what, long count) {
	}

	List<Usage> usages(Target target, long id);
}
