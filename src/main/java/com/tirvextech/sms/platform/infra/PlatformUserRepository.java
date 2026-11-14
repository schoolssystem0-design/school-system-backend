package com.selectcode.sms.platform.infra;

import com.selectcode.sms.platform.domain.PlatformUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

	@Query("select u from PlatformUser u where lower(u.email) = lower(?1)")
	Optional<PlatformUser> findByEmailIgnoreCase(String email);
}
