package com.selectcode.sms.academics.app;

import com.selectcode.sms.academics.domain.ActiveStatus;
import com.selectcode.sms.academics.domain.ClassSubject;
import com.selectcode.sms.academics.domain.SchoolClass;
import com.selectcode.sms.academics.domain.Section;
import com.selectcode.sms.academics.domain.Subject;
import com.selectcode.sms.academics.infra.ClassSubjectRepository;
import com.selectcode.sms.academics.infra.SchoolClassRepository;
import com.selectcode.sms.academics.infra.SectionRepository;
import com.selectcode.sms.academics.infra.SubjectRepository;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.usage.UsageContributor;
import com.selectcode.sms.shared.usage.UsageContributor.Target;
import com.selectcode.sms.shared.usage.UsageContributor.Usage;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.AcademicYearLookup;
import com.selectcode.sms.shared.year.YearContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Classes, sections, subjects and per-year class subjects (FR 9). */
@Service
public class AcademicsService {

	public record SectionView(long id, long classId, String name, String status, long version) {

		static SectionView of(Section s) {
			return new SectionView(s.getId(), s.getClassId(), s.getName(), s.getStatus().name(), s.getVersion());
		}
	}

	public record ClassView(long id, String name, String shortName, int displayOrder, String gradingMode,
			String status, List<SectionView> sections, long version) {
	}

	public record SubjectView(long id, String name, String shortName, String status, long version) {

		static SubjectView of(Subject s) {
			return new SubjectView(s.getId(), s.getName(), s.getShortName(), s.getStatus().name(), s.getVersion());
		}
	}

	public record ClassSubjectView(long id, long subjectId, String subjectName, BigDecimal maxMarks,
			BigDecimal passingMarks, int displayOrder, String status) {
	}

	public record ClassSubjectInput(long subjectId, BigDecimal maxMarks, BigDecimal passingMarks) {
	}

	private final SchoolClassRepository classes;

	private final SectionRepository sections;

	private final SubjectRepository subjects;

	private final ClassSubjectRepository classSubjects;

	private final List<UsageContributor> usageContributors;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final AuditService audit;

	private final Clock clock;

	AcademicsService(SchoolClassRepository classes, SectionRepository sections, SubjectRepository subjects,
			ClassSubjectRepository classSubjects, List<UsageContributor> usageContributors, YearContext yearContext,
			AcademicYearLookup years, AuditService audit, Clock clock) {
		this.classes = classes;
		this.sections = sections;
		this.subjects = subjects;
		this.classSubjects = classSubjects;
		this.usageContributors = usageContributors;
		this.yearContext = yearContext;
		this.years = years;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ classes

	@Transactional(readOnly = true)
	public List<ClassView> listClasses() {
		Map<Long, List<SectionView>> byClass = sections.findAllByOrderByNameAsc()
			.stream()
			.map(SectionView::of)
			.collect(Collectors.groupingBy(SectionView::classId));
		return classes.findAllByOrderByDisplayOrderAscNameAsc()
			.stream()
			.map(c -> classView(c, byClass.getOrDefault(c.getId(), List.of())))
			.toList();
	}

	@Transactional
	public ClassView createClass(String name, String shortName, SchoolClass.GradingMode gradingMode) {
		String cleanName = required(name, "Class name");
		ensureUniqueClassName(cleanName, null);
		int nextOrder = classes.findAll().stream().mapToInt(SchoolClass::getDisplayOrder).max().orElse(0) + 1;
		SchoolClass saved = classes.save(new SchoolClass(cleanName, shortOr(shortName, cleanName), nextOrder,
				gradingMode == null ? SchoolClass.GradingMode.MARKS : gradingMode));
		audit.record("CLASS_CREATED", "school_class", saved.getId(), null, Map.of("name", cleanName));
		return classView(saved, List.of());
	}

	@Transactional
	public ClassView updateClass(long id, String name, String shortName, SchoolClass.GradingMode gradingMode,
			long version) {
		SchoolClass c = findClass(id);
		checkVersion(c.getVersion(), version);
		String cleanName = required(name, "Class name");
		ensureUniqueClassName(cleanName, id);
		Map<String, Object> before = Map.of("name", c.getName(), "shortName", c.getShortName(), "gradingMode",
				c.getGradingMode().name());
		// Renaming changes the name everywhere; history stays linked by ID (FR 9.6).
		c.change(cleanName, shortOr(shortName, cleanName), gradingMode == null ? c.getGradingMode() : gradingMode,
				clock.instant());
		classes.flush();
		audit.record("CLASS_UPDATED", "school_class", id, before,
				Map.of("name", c.getName(), "shortName", c.getShortName(), "gradingMode", c.getGradingMode().name()));
		return classView(c, sections.findByClassIdOrderByNameAsc(id).stream().map(SectionView::of).toList());
	}

	/** Sets the display order: classes are listed youngest to oldest everywhere. */
	@Transactional
	public List<ClassView> reorder(List<Long> orderedIds) {
		Map<Long, SchoolClass> all = classes.findAll()
			.stream()
			.collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
		if (orderedIds == null || orderedIds.size() != all.size() || !all.keySet().equals(new HashSet<>(orderedIds))) {
			throw ApiException.businessRule("REORDER_INCOMPLETE", "Send every class exactly once");
		}
		for (int i = 0; i < orderedIds.size(); i++) {
			all.get(orderedIds.get(i)).moveTo(i + 1, clock.instant());
		}
		audit.record("CLASSES_REORDERED", "school_class", null, null, Map.of("order", orderedIds));
		return listClasses();
	}

	@Transactional
	public ClassView setClassStatus(long id, ActiveStatus status) {
		SchoolClass c = findClass(id);
		c.setStatus(status, clock.instant());
		audit.record(status == ActiveStatus.ACTIVE ? "CLASS_ACTIVATED" : "CLASS_DEACTIVATED", "school_class", id,
				null, null);
		return classView(c, sections.findByClassIdOrderByNameAsc(id).stream().map(SectionView::of).toList());
	}

	@Transactional
	public void deleteClass(long id) {
		SchoolClass c = findClass(id);
		ensureUnused(Target.CLASS, id);
		classes.delete(c);
		audit.record("CLASS_DELETED", "school_class", id, Map.of("name", c.getName()), null);
	}

	// ----------------------------------------------------------------- sections

	@Transactional
	public SectionView createSection(long classId, String name) {
		findClass(classId);
		String cleanName = required(name, "Section name");
		ensureUniqueSectionName(classId, cleanName, null);
		Section saved = sections.save(new Section(classId, cleanName));
		audit.record("SECTION_CREATED", "section", saved.getId(), null, Map.of("classId", classId, "name", cleanName));
		return SectionView.of(saved);
	}

	@Transactional
	public SectionView renameSection(long id, String name, long version) {
		Section s = findSection(id);
		checkVersion(s.getVersion(), version);
		String cleanName = required(name, "Section name");
		ensureUniqueSectionName(s.getClassId(), cleanName, id);
		String before = s.getName();
		s.rename(cleanName, clock.instant());
		sections.flush();
		audit.record("SECTION_UPDATED", "section", id, Map.of("name", before), Map.of("name", cleanName));
		return SectionView.of(s);
	}

	@Transactional
	public SectionView setSectionStatus(long id, ActiveStatus status) {
		Section s = findSection(id);
		s.setStatus(status, clock.instant());
		audit.record(status == ActiveStatus.ACTIVE ? "SECTION_ACTIVATED" : "SECTION_DEACTIVATED", "section", id, null,
				null);
		return SectionView.of(s);
	}

	@Transactional
	public void deleteSection(long id) {
		Section s = findSection(id);
		ensureUnused(Target.SECTION, id);
		sections.delete(s);
		audit.record("SECTION_DELETED", "section", id, Map.of("name", s.getName()), null);
	}

	// ----------------------------------------------------------------- subjects

	@Transactional(readOnly = true)
	public List<SubjectView> listSubjects() {
		return subjects.findAllByOrderByNameAsc().stream().map(SubjectView::of).toList();
	}

	@Transactional
	public SubjectView createSubject(String name, String shortName) {
		String cleanName = required(name, "Subject name");
		ensureUniqueSubjectName(cleanName, null);
		Subject saved = subjects.save(new Subject(cleanName, shortOr(shortName, cleanName)));
		audit.record("SUBJECT_CREATED", "subject", saved.getId(), null, Map.of("name", cleanName));
		return SubjectView.of(saved);
	}

	@Transactional
	public SubjectView updateSubject(long id, String name, String shortName, long version) {
		Subject s = findSubject(id);
		checkVersion(s.getVersion(), version);
		String cleanName = required(name, "Subject name");
		ensureUniqueSubjectName(cleanName, id);
		Map<String, Object> before = Map.of("name", s.getName(), "shortName", s.getShortName());
		s.change(cleanName, shortOr(shortName, cleanName), clock.instant());
		subjects.flush();
		audit.record("SUBJECT_UPDATED", "subject", id, before, Map.of("name", cleanName, "shortName", s.getShortName()));
		return SubjectView.of(s);
	}

	@Transactional
	public SubjectView setSubjectStatus(long id, ActiveStatus status) {
		Subject s = findSubject(id);
		s.setStatus(status, clock.instant());
		audit.record(status == ActiveStatus.ACTIVE ? "SUBJECT_ACTIVATED" : "SUBJECT_DEACTIVATED", "subject", id, null,
				null);
		return SubjectView.of(s);
	}

	@Transactional
	public void deleteSubject(long id) {
		Subject s = findSubject(id);
		ensureUnused(Target.SUBJECT, id);
		subjects.delete(s);
		audit.record("SUBJECT_DELETED", "subject", id, Map.of("name", s.getName()), null);
	}

	// --------------------------------------------------------- class subjects

	@Transactional(readOnly = true)
	public List<ClassSubjectView> classSubjects(long yearId, long classId) {
		years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		findClass(classId);
		Map<Long, Subject> subjectById = subjects.findAll()
			.stream()
			.collect(Collectors.toMap(Subject::getId, Function.identity()));
		return classSubjects.findByAcademicYearIdAndClassIdOrderByDisplayOrderAsc(yearId, classId)
			.stream()
			.map(cs -> new ClassSubjectView(cs.getId(), cs.getSubjectId(),
					subjectById.get(cs.getSubjectId()).getName(), cs.getMaxMarks(), cs.getPassingMarks(),
					cs.getDisplayOrder(), cs.getStatus().name()))
			.toList();
	}

	/**
	 * Sets the subjects of a class for a year, in display order. Subjects left out are deactivated (kept for history;
	 * marks entered against them in later phases stay valid).
	 */
	@Transactional
	public List<ClassSubjectView> saveClassSubjects(long yearId, long classId, List<ClassSubjectInput> inputs) {
		AcademicYearLookup.YearRef year = years.findById(yearId)
			.orElseThrow(() -> ApiException.notFound("Academic year"));
		yearContext.requireWritable(year);
		findClass(classId);
		Set<Long> seen = new HashSet<>();
		Set<Long> knownSubjects = subjects.findAll().stream().map(Subject::getId).collect(Collectors.toSet());
		for (ClassSubjectInput in : inputs) {
			if (!seen.add(in.subjectId())) {
				throw ApiException.businessRule("SUBJECT_DUPLICATE", "Each subject can be added once per class");
			}
			if (!knownSubjects.contains(in.subjectId())) {
				throw ApiException.businessRule("SUBJECT_UNKNOWN", "Unknown subject");
			}
			validateMarks(in.maxMarks(), in.passingMarks());
		}
		Map<Long, ClassSubject> existing = classSubjects
			.findByAcademicYearIdAndClassIdOrderByDisplayOrderAsc(yearId, classId)
			.stream()
			.collect(Collectors.toMap(ClassSubject::getSubjectId, Function.identity()));
		List<Object> before = existing.values()
			.stream()
			.map(cs -> (Object) Map.of("subjectId", cs.getSubjectId(), "max", cs.getMaxMarks().toPlainString(),
					"status", cs.getStatus().name()))
			.toList();
		for (int i = 0; i < inputs.size(); i++) {
			ClassSubjectInput in = inputs.get(i);
			ClassSubject cs = existing.remove(in.subjectId());
			if (cs == null) {
				classSubjects.save(new ClassSubject(yearId, classId, in.subjectId(), in.maxMarks(), in.passingMarks(),
						i + 1));
			}
			else {
				cs.change(in.maxMarks(), in.passingMarks(), i + 1, clock.instant());
			}
		}
		existing.values().forEach(cs -> cs.setStatus(ActiveStatus.INACTIVE, clock.instant()));
		classSubjects.flush();
		List<Object> after = new ArrayList<>();
		inputs.forEach(in -> after.add(Map.of("subjectId", in.subjectId(), "max", in.maxMarks().toPlainString())));
		audit.record("CLASS_SUBJECTS_SAVED", "school_class", classId, Map.of("yearId", yearId, "subjects", before),
				Map.of("yearId", yearId, "subjects", after));
		return classSubjects(yearId, classId);
	}

	// ----------------------------------------------------------------- helpers

	/** Where-used counts from every module (FR 9.7). */
	@Transactional(readOnly = true)
	public List<Usage> usages(Target target, long id) {
		return usageContributors.stream()
			.flatMap(c -> c.usages(target, id).stream())
			.filter(u -> u.count() > 0)
			.toList();
	}

	private void ensureUnused(Target target, long id) {
		List<Usage> used = usages(target, id);
		if (!used.isEmpty()) {
			String detail = used.stream().map(u -> u.what() + ": " + u.count()).collect(Collectors.joining(", "));
			throw ApiException.conflict("IN_USE", "Used by " + detail + ". Deactivate it instead.");
		}
	}

	private static void validateMarks(BigDecimal max, BigDecimal passing) {
		if (max == null || max.signum() <= 0 || max.compareTo(new BigDecimal("1000")) > 0 || max.scale() > 2) {
			throw ApiException.businessRule("MAX_MARKS_INVALID", "Maximum marks must be between 0.01 and 1000");
		}
		if (passing != null && (passing.signum() < 0 || passing.compareTo(max) > 0)) {
			throw ApiException.businessRule("PASSING_MARKS_INVALID", "Passing marks must be between 0 and the maximum");
		}
	}

	private ClassView classView(SchoolClass c, List<SectionView> classSections) {
		return new ClassView(c.getId(), c.getName(), c.getShortName(), c.getDisplayOrder(), c.getGradingMode().name(),
				c.getStatus().name(), classSections, c.getVersion());
	}

	private void ensureUniqueClassName(String name, Long selfId) {
		boolean taken = classes.findAll()
			.stream()
			.anyMatch(c -> c.getName().equalsIgnoreCase(name) && !c.getId().equals(selfId));
		if (taken) {
			throw ApiException.conflict("NAME_TAKEN", "A class with this name already exists");
		}
	}

	private void ensureUniqueSectionName(long classId, String name, Long selfId) {
		boolean taken = sections.findByClassIdOrderByNameAsc(classId)
			.stream()
			.anyMatch(s -> s.getName().equalsIgnoreCase(name) && !s.getId().equals(selfId));
		if (taken) {
			throw ApiException.conflict("NAME_TAKEN", "This class already has a section with this name");
		}
	}

	private void ensureUniqueSubjectName(String name, Long selfId) {
		boolean taken = subjects.findAll()
			.stream()
			.anyMatch(s -> s.getName().equalsIgnoreCase(name) && !s.getId().equals(selfId));
		if (taken) {
			throw ApiException.conflict("NAME_TAKEN", "A subject with this name already exists");
		}
	}

	private SchoolClass findClass(long id) {
		return classes.findById(id).orElseThrow(() -> ApiException.notFound("Class"));
	}

	private Section findSection(long id) {
		return sections.findById(id).orElseThrow(() -> ApiException.notFound("Section"));
	}

	private Subject findSubject(long id) {
		return subjects.findById(id).orElseThrow(() -> ApiException.notFound("Subject"));
	}

	private static void checkVersion(long actual, long expected) {
		if (actual != expected) {
			throw ApiException.conflict("CONFLICT_VERSION", "This was changed by someone else. Reload and try again.");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw ApiException.businessRule("FIELD_REQUIRED", field + " is required");
		}
		return value.trim();
	}

	private static String shortOr(String shortName, String name) {
		if (shortName != null && !shortName.isBlank()) {
			return shortName.trim();
		}
		String compact = name.replaceAll("(?i)^class\\s+", "").trim();
		return (compact.length() > 20 ? compact.substring(0, 20) : compact).toUpperCase(Locale.ROOT);
	}
}
