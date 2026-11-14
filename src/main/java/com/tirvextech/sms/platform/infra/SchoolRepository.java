package com.tirvextech.sms.platform.infra;

import com.tirvextech.sms.platform.domain.School;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {

	Optional<School> findByCode(String code);

	List<School> findAllByOrderByNameAsc();
}
