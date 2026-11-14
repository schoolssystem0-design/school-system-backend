package com.tirvextech.sms.admissions.infra;

import com.tirvextech.sms.admissions.domain.Enquiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {
}
