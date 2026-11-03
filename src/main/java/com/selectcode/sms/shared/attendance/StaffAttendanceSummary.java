package com.selectcode.sms.shared.attendance;

import java.time.YearMonth;
import java.util.Optional;

/**
 * A staff member's approved attendance totals for a month, used by payroll (FR 13.12). Implemented by the attendance
 * module (Phase 5); until then payroll figures are entered on the draft salary sheet.
 */
public interface StaffAttendanceSummary {

	record MonthTotals(int absentDays, int halfDays, int lateDays, int leaveDays) {
	}

	/** Empty when the month's attendance for this staff member is not approved yet. */
	Optional<MonthTotals> approvedTotals(long staffId, YearMonth month);
}
