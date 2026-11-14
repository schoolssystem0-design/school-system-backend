package com.selectcode.sms.students.app;

import com.selectcode.sms.shared.usage.UsageContributor;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Classes and sections with student history cannot be deleted, only deactivated (FR 9.7). */
@Component
class StudentUsage implements UsageContributor {

	private final JdbcTemplate jdbc;

	StudentUsage(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Usage> usages(Target target, long id) {
		return switch (target) {
			case CLASS -> List.of(new Usage("Student enrolments",
					count("select count(*) from enrolment where class_id = ?", id)),
					new Usage("Admission enquiries", count("select count(*) from enquiry where class_applied_id = ?", id)));
			case SECTION -> List.of(new Usage("Student enrolments",
					count("select count(*) from enrolment where section_id = ?", id)),
					new Usage("Teaching assignments", count("select count(*) from teaching_assignment where section_id = ?", id)));
			case SUBJECT -> List.of(new Usage("Teaching assignments",
					count("select count(*) from teaching_assignment where subject_id = ?", id)));
		};
	}

	private long count(String sql, long id) {
		Long n = jdbc.queryForObject(sql, Long.class, id);
		return n == null ? 0 : n;
	}
}
