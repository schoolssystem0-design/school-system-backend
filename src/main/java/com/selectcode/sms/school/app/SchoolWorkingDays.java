package com.selectcode.sms.school.app;

import com.selectcode.sms.school.infra.WorkingDayStore;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Working days across academic years, for payroll and attendance percentages. */
@Component
class SchoolWorkingDays implements WorkingDayCalendar {

	private final JdbcTemplate jdbc;

	private final CalendarService calendar;

	SchoolWorkingDays(JdbcTemplate jdbc, CalendarService calendar) {
		this.jdbc = jdbc;
		this.calendar = calendar;
	}

	@Override
	@Transactional(readOnly = true)
	public int countWorkingDays(LocalDate from, LocalDate to) {
		record Year(long id, LocalDate start, LocalDate end) {
		}
		List<Year> years = jdbc.query("""
				select id, start_date, end_date from academic_year where start_date <= ? and end_date >= ?
				order by start_date""", (rs, i) -> new Year(rs.getLong(1), rs.getDate(2).toLocalDate(),
				rs.getDate(3).toLocalDate()), Date.valueOf(to), Date.valueOf(from));
		int count = 0;
		var defaultWeek = WorkingDayStore.defaultWeek();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			LocalDate day = d;
			if (years.stream().noneMatch(y -> !day.isBefore(y.start()) && !day.isAfter(y.end()))
					&& defaultWeek.get(day.getDayOfWeek()) != WorkingDayStore.DayType.OFF) {
				count++;
			}
		}
		for (Year y : years) {
			LocalDate start = from.isAfter(y.start()) ? from : y.start();
			LocalDate end = to.isBefore(y.end()) ? to : y.end();
			count += calendar.countWorkingDays(y.id(), start, end);
		}
		return count;
	}
}
