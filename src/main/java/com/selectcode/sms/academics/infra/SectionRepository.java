package com.selectcode.sms.academics.infra;

import com.selectcode.sms.academics.domain.Section;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<Section, Long> {

	java.util.List<Section> findByClassIdOrderByNameAsc(long classId);
	java.util.List<Section> findAllByOrderByNameAsc();
	long countByClassId(long classId);
}
