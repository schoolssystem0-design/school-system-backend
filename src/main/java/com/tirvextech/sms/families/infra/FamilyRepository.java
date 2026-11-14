package com.tirvextech.sms.families.infra;

import com.tirvextech.sms.families.domain.Family;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyRepository extends JpaRepository<Family, Long> {
}
