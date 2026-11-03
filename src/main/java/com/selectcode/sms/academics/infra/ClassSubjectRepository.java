package com.selectcode.sms.academics.infra;

import com.selectcode.sms.academics.domain.ClassSubject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassSubjectRepository extends JpaRepository<ClassSubject, Long> {

	java.util.List<ClassSubject> findByAcademicYearIdAndClassIdOrderByDisplayOrderAsc(long yearId, long classId);
	java.util.List<ClassSubject> findByAcademicYearIdOrderByClassIdAscDisplayOrderAsc(long yearId);
	long countByClassId(long classId);
	long countBySubjectId(long subjectId);
}
