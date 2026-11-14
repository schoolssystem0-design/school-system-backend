package com.selectcode.sms.school;

import com.selectcode.sms.school.infra.SchoolProfileStore;
import org.springframework.stereotype.Service;

/** Facade for the platform module: initial data of a newly created school (tenant = the new school). */
@Service
public class SchoolSetup {

	private final SchoolProfileStore profiles;

	SchoolSetup(SchoolProfileStore profiles) {
		this.profiles = profiles;
	}

	public void initialise(String schoolName) {
		profiles.create(schoolName);
	}
}
