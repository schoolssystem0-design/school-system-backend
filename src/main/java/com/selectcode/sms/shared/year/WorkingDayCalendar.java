package com.selectcode.sms.shared.year;

import java.time.LocalDate;

/** Working days of the current school (working week minus holidays and vacations). Implemented by the school module. */
public interface WorkingDayCalendar {

	/** Working days between two dates, inclusive. Days outside every academic year use the default week. */
	int countWorkingDays(LocalDate from, LocalDate to);

	/** Whether the school is open on the date (working weekday, not a holiday or vacation). */
	default boolean isWorkingDay(LocalDate date) {
		return countWorkingDays(date, date) == 1;
	}

	/** The working days between two dates, inclusive, in order. */
	default java.util.List<LocalDate> workingDays(LocalDate from, LocalDate to) {
		java.util.List<LocalDate> days = new java.util.ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			if (isWorkingDay(d)) {
				days.add(d);
			}
		}
		return days;
	}
}
