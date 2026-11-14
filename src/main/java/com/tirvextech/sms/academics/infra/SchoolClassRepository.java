package com.tirvextech.sms.academics.infra;

import com.tirvextech.sms.academics.domain.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, Long> {

	java.util.List<SchoolClass> findAllByOrderByDisplayOrderAscNameAsc();
}
