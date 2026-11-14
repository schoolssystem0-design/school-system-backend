package com.selectcode.sms.shared.attendance;

import java.time.YearMonth;
import java.util.Map;

/** Unexplained absences (status A on working days) per student in a month, for the absent fine (FR 10.12). */
public interface StudentAbsences {

	Map<Long, Integer> absencesInMonth(YearMonth month);
}
