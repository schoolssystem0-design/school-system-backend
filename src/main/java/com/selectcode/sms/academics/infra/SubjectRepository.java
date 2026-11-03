package com.selectcode.sms.academics.infra;

import com.selectcode.sms.academics.domain.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, Long> {

	java.util.List<Subject> findAllByOrderByNameAsc();
}
