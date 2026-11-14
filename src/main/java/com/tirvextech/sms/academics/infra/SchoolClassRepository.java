package com.selectcode.sms.academics.infra;

import com.selectcode.sms.academics.domain.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, Long> {

	java.util.List<SchoolClass> findAllByOrderByDisplayOrderAscNameAsc();
}
