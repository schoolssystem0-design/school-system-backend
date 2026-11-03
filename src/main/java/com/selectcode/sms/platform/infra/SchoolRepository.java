package com.selectcode.sms.platform.infra;

import com.selectcode.sms.platform.domain.School;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {

	Optional<School> findByCode(String code);

	List<School> findAllByOrderByNameAsc();
}
