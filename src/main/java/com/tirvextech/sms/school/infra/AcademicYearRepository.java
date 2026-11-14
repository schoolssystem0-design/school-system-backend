package com.tirvextech.sms.school.infra;

import com.tirvextech.sms.school.domain.AcademicYear;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicYearRepository extends JpaRepository<AcademicYear, Long> {

	List<AcademicYear> findAllByOrderByStartDateDesc();

	Optional<AcademicYear> findByStatus(AcademicYear.Status status);

	Optional<AcademicYear> findByNameIgnoreCase(String name);
}
