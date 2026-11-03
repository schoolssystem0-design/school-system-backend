package com.selectcode.sms.iam.infra;

import com.selectcode.sms.iam.domain.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

	@Query("select count(e) from LoginEvent e where e.userId = ?1 and e.result = 'SUCCESS'")
	long countSuccessful(long userId);

	@Query("select count(e) from LoginEvent e where e.userId = ?1 and e.result = 'SUCCESS' and e.ipPrefix = ?2")
	long countSuccessfulFromPrefix(long userId, String prefix);
}
