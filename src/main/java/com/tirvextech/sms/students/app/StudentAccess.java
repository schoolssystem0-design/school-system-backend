package com.selectcode.sms.students.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.shared.security.ActorType;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may see which students (FR 2.5, 5.11): teachers with the "assigned classes" scope see only students of sections
 * they teach in the selected year; private details need {@code students.private.view}.
 */
@Component
public class StudentAccess {

	private static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private final SchoolUserAdministration users;

	private final AcademicStructure structure;

	private final Clock clock;

	StudentAccess(SchoolUserAdministration users, AcademicStructure structure, Clock clock) {
		this.users = users;
		this.structure = structure;
		this.clock = clock;
	}

	/** Sections the user may see in the year; empty Optional = all sections. */
	public Optional<Set<Long>> visibleSections(YearRef year) {
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
		return Optional.of(structure.sectionsTaughtBy(staffId, year.id(), referenceDate(year)));
	}

	/** Throws 404 (not 403) for a student outside the user's sections, so their existence is not revealed. */
	public void checkSection(YearRef year, Long sectionId) {
		var visible = visibleSections(year);
		if (visible.isPresent() && (sectionId == null || !visible.get().contains(sectionId))) {
			throw ApiException.notFound("Student");
		}
	}

	public boolean canSeePrivate() {
		return CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);
	}

	/** Today, clamped into the year (lists for a past or future year show the class at its end or start). */
	public LocalDate referenceDate(YearRef year) {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		if (today.isBefore(year.startDate())) {
			return year.startDate();
		}
		if (today.isAfter(year.endDate())) {
			return year.endDate();
		}
		return today;
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}
}
