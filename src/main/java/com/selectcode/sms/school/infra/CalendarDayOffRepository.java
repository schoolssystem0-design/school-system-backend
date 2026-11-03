package com.selectcode.sms.school.infra;

import com.selectcode.sms.school.domain.CalendarDayOff;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CalendarDayOffRepository extends JpaRepository<CalendarDayOff, Long> {

	List<CalendarDayOff> findByAcademicYearIdOrderByDateFrom(long academicYearId);

	@Query("select d from CalendarDayOff d where d.dateFrom <= ?2 and d.dateTo >= ?1 order by d.dateFrom")
	List<CalendarDayOff> findOverlapping(LocalDate from, LocalDate to);
}
