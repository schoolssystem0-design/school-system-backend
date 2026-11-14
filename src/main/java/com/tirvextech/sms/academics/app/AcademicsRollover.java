package com.selectcode.sms.academics.app;

import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.RolloverStep;
import java.sql.Date;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rollover steps owned by academics: subjects with maximum marks, and teaching assignments. */
@Configuration(proxyBeanMethods = false)
class AcademicsRollover {

	@Bean
	RolloverStep classSubjectsRollover(JdbcTemplate jdbc) {
		return new RolloverStep() {
			@Override
			public String key() {
				return "CLASS_SUBJECTS";
			}

			@Override
			public String label() {
				return "Subjects and maximum/passing marks of every class";
			}

			@Override
			public int copy(YearRef from, YearRef to) {
				return jdbc.update("""
						insert into class_subject (school_id, academic_year_id, class_id, subject_id, max_marks,
						                           passing_marks, display_order, status)
						select school_id, ?, class_id, subject_id, max_marks, passing_marks, display_order, status
						from class_subject cs
						where academic_year_id = ? and status = 'ACTIVE'
						  and not exists (select 1 from class_subject x where x.academic_year_id = ?
						                  and x.class_id = cs.class_id and x.subject_id = cs.subject_id)""", to.id(),
						from.id(), to.id());
			}
		};
	}

	@Bean
	RolloverStep teachingAssignmentsRollover(JdbcTemplate jdbc) {
		return new RolloverStep() {
			@Override
			public String key() {
				return "TEACHING_ASSIGNMENTS";
			}

			@Override
			public String label() {
				return "Class teachers and subject teachers (still employed)";
			}

			@Override
			public int copy(YearRef from, YearRef to) {
				return jdbc.update("""
						insert into teaching_assignment (school_id, academic_year_id, section_id, subject_id, staff_id,
						                                 effective_from)
						select a.school_id, ?, a.section_id, a.subject_id, a.staff_id, ?
						from teaching_assignment a join staff s on s.id = a.staff_id
						where a.academic_year_id = ? and s.status = 'ACTIVE'
						  and (a.effective_to is null or a.effective_to >= ?)
						  and not exists (select 1 from teaching_assignment x where x.academic_year_id = ?
						                  and x.section_id = a.section_id
						                  and x.subject_id is not distinct from a.subject_id and x.effective_to is null)""",
						to.id(), Date.valueOf(to.startDate()), from.id(), Date.valueOf(from.endDate()), to.id());
			}
		};
	}
}
