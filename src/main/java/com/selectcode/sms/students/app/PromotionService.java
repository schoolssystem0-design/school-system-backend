package com.selectcode.sms.students.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.students.StudentService;
import com.selectcode.sms.students.infra.Enrolments;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves students into the next academic year (FR 5.5): promoted, repeating the class, or leaving. Last year's class
 * and results stay unchanged; the new year gets new enrolments from its start date.
 */
@Service
public class PromotionService {

	public enum Decision {
		PROMOTE, REPEAT, LEAVE
	}

	public record Candidate(long studentId, String grNo, String name, String sectionName, Long suggestedClassId,
			String suggestedClassName, String alreadyIn) {
	}

	public record Preview(long fromClassId, String fromClassName, Long nextClassId, String nextClassName,
			List<AcademicStructure.SectionRef> nextSections, List<AcademicStructure.SectionRef> sameSections,
			List<Candidate> students) {
	}

	public record Item(long studentId, Decision decision, Long toSectionId, String reason) {
	}

	public record PromotionResult(int promoted, int repeating, int left) {
	}

	private final AcademicStructure structure;

	private final AcademicYearLookup years;

	private final YearContext yearContext;

	private final StudentService students;

	private final Enrolments enrolments;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	PromotionService(AcademicStructure structure, AcademicYearLookup years, YearContext yearContext,
			StudentService students, Enrolments enrolments, AuditService audit, JdbcTemplate jdbc) {
		this.structure = structure;
		this.years = years;
		this.yearContext = yearContext;
		this.students = students;
		this.enrolments = enrolments;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Preview preview(long fromYearId, long toYearId, long classId) {
		YearRef from = year(fromYearId);
		YearRef to = year(toYearId);
		checkOrder(from, to);
		var fromClass = structure.requireClass(classId);
		var next = structure.nextClass(classId);
		List<Candidate> list = jdbc.query("""
				select s.id, s.gr_no, s.name, sec.name,
				       (select c2.name || ' - ' || s2.name from enrolment e2
				        join school_class c2 on c2.id = e2.class_id join section s2 on s2.id = e2.section_id
				        where e2.student_id = s.id and e2.academic_year_id = ? limit 1)
				from enrolment e join student s on s.id = e.student_id join section sec on sec.id = e.section_id
				where e.academic_year_id = ? and e.class_id = ? and s.status = 'ACTIVE'
				  and (e.effective_to is null or e.effective_to >= ?)
				order by sec.name, s.name""",
				(rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						next.map(AcademicStructure.ClassRef::id).orElse(null),
						next.map(AcademicStructure.ClassRef::name).orElse(null), rs.getString(5)),
				to.id(), from.id(), classId, Date.valueOf(from.endDate()));
		return new Preview(classId, fromClass.name(), next.map(AcademicStructure.ClassRef::id).orElse(null),
				next.map(AcademicStructure.ClassRef::name).orElse(null),
				next.map(n -> structure.sectionsOfClass(n.id())).orElse(List.of()), structure.sectionsOfClass(classId),
				list);
	}

	@Transactional
	public PromotionResult apply(long fromYearId, long toYearId, List<Item> items) {
		YearRef from = year(fromYearId);
		YearRef to = year(toYearId);
		checkOrder(from, to);
		yearContext.requireWritable(to);
		yearContext.requireWritable(from);
		int promoted = 0;
		int repeating = 0;
		int left = 0;
		for (Item item : items) {
			var student = students.entity(item.studentId());
			if (!"ACTIVE".equals(student.getStatus())) {
				throw ApiException.conflict("NOT_ACTIVE", student.getName() + " is not an active student");
			}
			var last = enrolments.latestInYear(student.getId(), from.id())
				.orElseThrow(() -> ApiException.businessRule("NOT_ENROLLED", student.getName() + " is not in " + from.name()));
			if (enrolments.latestInYear(student.getId(), to.id()).isPresent()) {
				throw ApiException.conflict("ALREADY_IN_YEAR", student.getName() + " already has a class in " + to.name());
			}
			if (item.decision() == Decision.LEAVE) {
				students.withdraw(student.getId(), from.endDate(),
						item.reason() == null || item.reason().isBlank() ? "Left at the end of " + from.name() : item.reason());
				left++;
				continue;
			}
			if (item.toSectionId() == null) {
				throw ApiException.businessRule("SECTION_REQUIRED", "Choose the new section for " + student.getName());
			}
			var section = structure.requireSection(item.toSectionId());
			if (item.decision() == Decision.REPEAT && section.classId() != last.classId()) {
				throw ApiException.businessRule("REPEAT_SAME_CLASS", student.getName() + " repeats: choose a section of the same class");
			}
			if (item.decision() == Decision.PROMOTE && section.classId() == last.classId()) {
				throw ApiException.businessRule("PROMOTE_NEXT_CLASS", student.getName() + " is promoted: choose a section of a higher class");
			}
			if (last.effectiveTo() == null || last.effectiveTo().isAfter(from.endDate())) {
				enrolments.close(last.id(), from.endDate());
			}
			enrolments.create(student.getId(), to.id(), section.classId(), section.id(), null, to.startDate(), null,
					item.decision() == Decision.PROMOTE ? "PROMOTION" : "REPEAT", item.reason());
			if (item.decision() == Decision.PROMOTE) {
				promoted++;
			}
			else {
				repeating++;
			}
		}
		Map<String, Object> summary = new HashMap<>();
		summary.put("from", from.name());
		summary.put("to", to.name());
		summary.put("promoted", promoted);
		summary.put("repeating", repeating);
		summary.put("left", left);
		audit.record("STUDENTS_PROMOTED", "academic_year", to.id(), null, summary);
		return new PromotionResult(promoted, repeating, left);
	}

	private YearRef year(long id) {
		return years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
	}

	private static void checkOrder(YearRef from, YearRef to) {
		if (!to.startDate().isAfter(from.endDate().minusDays(1))) {
			throw ApiException.businessRule("YEAR_ORDER", to.name() + " must start after " + from.name() + " ends");
		}
	}
}
