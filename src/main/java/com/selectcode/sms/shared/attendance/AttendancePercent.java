package com.selectcode.sms.shared.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/** A student's attendance percentage (DECISIONS D-09) over a period, for report cards (FR 14.13). */
public interface AttendancePercent {

	Optional<String> percent(long studentId, LocalDate from, LocalDate to);

	/** (P + L + ½ HD) ÷ (marked days − leave) to one decimal (D-09); null when nothing counts. */
	static String of(int present, int late, int halfDays, int markedDays, int leave) {
		int base = markedDays - leave;
		if (base <= 0) {
			return null;
		}
		BigDecimal attended = BigDecimal.valueOf(present + late).add(BigDecimal.valueOf(halfDays).multiply(new BigDecimal("0.5")));
		return attended.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(base), 1, RoundingMode.HALF_UP).toPlainString();
	}
}
