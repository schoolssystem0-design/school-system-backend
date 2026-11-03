package com.selectcode.sms.staff.infra;

import com.selectcode.sms.staff.domain.Staff;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StaffRepository extends JpaRepository<Staff, Long> {

	@Query("""
			select s from Staff s
			where (:q = '' or lower(s.name) like concat('%', :q, '%') or lower(s.employeeNo) = :q
			       or lower(s.designation) like concat('%', :q, '%'))
			  and s.category in :categories and s.status in :statuses
			order by s.name""")
	Page<Staff> search(String q, Collection<String> categories, Collection<String> statuses, Pageable pageable);
}
