package com.selectcode.sms.admissions.infra;

import com.selectcode.sms.admissions.domain.Enquiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {
}
