package com.selectcode.sms.families.infra;

import com.selectcode.sms.families.domain.Family;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyRepository extends JpaRepository<Family, Long> {
}
