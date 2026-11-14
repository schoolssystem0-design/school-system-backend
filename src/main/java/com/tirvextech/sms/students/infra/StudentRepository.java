package com.selectcode.sms.students.infra;

import com.selectcode.sms.students.domain.Student;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StudentRepository extends JpaRepository<Student, Long> {

	@Query("select s from Student s where lower(s.grNo) = lower(?1)")
	Optional<Student> findByGrNo(String grNo);
}
