package com.selectcode.sms.attendance.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which registers a user may see and take (FR 2.5, 13.1). Users with the "all classes" scope: every section.
 * Teachers ("assigned classes"): they see the sections they teach and take the register only where they are the
 * class teacher on that date (QA-STF-001). A section outside the user's reach is reported as not found.
 */
@Component
public class AttendanceAccess {

	private final SchoolUserAdministration users;

	private final AcademicStructure structure;

	private final AcademicYearLookup years;

	private final JdbcTemplate jdbc;

	AttendanceAccess(SchoolUserAdministration users, AcademicStructure structure, AcademicYearLookup years,
			JdbcTemplate jdbc) {
		this.users = users;
		this.structure = structure;
		this.years = years;
		this.jdbc = jdbc;
	}

	/** The academic year that contains the date. */
	public YearRef yearOf(LocalDate date) {
		List<Long> ids = jdbc.queryForList("select id from academic_year where start_date <= ? and end_date >= ?",
				Long.class, Date.valueOf(date), Date.valueOf(date));
		if (ids.isEmpty()) {
			throw ApiException.businessRule("NO_YEAR", "No academic year covers " + date);
		}
		return years.findById(ids.getFirst()).orElseThrow();
	}

	/** Sections the user may view on the date; empty Optional = all. */
	public Optional<Set<Long>> viewable(YearRef year, LocalDate date) {
		return scoped(year, date, false);
	}

	public void checkView(YearRef year, LocalDate date, long sectionId) {
		var allowed = viewable(year, date);
		if (allowed.isPresent() && !allowed.get().contains(sectionId)) {
			throw ApiException.notFound("Section");
		}
	}

	public boolean canMark(YearRef year, LocalDate date, long sectionId) {
		var allowed = scoped(year, date, true);
		return allowed.isEmpty() || allowed.get().contains(sectionId);
	}

	private Optional<Set<Long>> scoped(YearRef year, LocalDate date, boolean classTeacherOnly) {
		DataScope scope = DataScope.current();
		if (scope == DataScope.ALL) {
			return Optional.empty();
		}
		if (scope == DataScope.SELF) {
			return Optional.of(Set.of());
		}
		var actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			return Optional.empty();
		}
		Long staffId = users.staffIdOfUser(actor.actorId()).orElse(null);
		if (staffId == null) {
			return Optional.of(Set.of());
		}
		return Optional.of(classTeacherOnly ? structure.classTeacherSections(staffId, year.id(), date)
				: structure.sectionsTaughtBy(staffId, year.id(), date));
	}

	public String sectionLabel(long sectionId) {
		return structure.findSection(sectionId).map(AcademicStructure.SectionRef::label)
			.orElseThrow(() -> ApiException.notFound("Section"));
	}
}
