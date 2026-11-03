package com.selectcode.sms.iam.infra;

import com.selectcode.sms.iam.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

	Optional<Role> findByCode(String code);

	List<Role> findAllByOrderBySystemRoleDescNameAsc();

	List<Role> findBySystemRoleTrueAndTemplateVersionLessThan(int version);
}
