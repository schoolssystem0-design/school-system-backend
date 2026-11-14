package com.selectcode.sms.platform.infra;

import com.selectcode.sms.platform.domain.SchoolDomain;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SchoolDomainRepository extends JpaRepository<SchoolDomain, Long> {

	List<SchoolDomain> findBySchoolIdOrderByHostAsc(long schoolId);

	@Query("select d from SchoolDomain d where lower(d.host) = lower(?1)")
	Optional<SchoolDomain> findByHostIgnoreCase(String host);
}
