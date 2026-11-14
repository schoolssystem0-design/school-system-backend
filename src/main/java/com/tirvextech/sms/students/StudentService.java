package com.tirvextech.sms.students;

import com.tirvextech.sms.families.FamilyDetails;
import com.tirvextech.sms.academics.AcademicStructure;
import com.tirvextech.sms.families.FamilyService;
import com.tirvextech.sms.shared.audit.AuditService;
import com.tirvextech.sms.shared.events.StudentEvents;
import com.tirvextech.sms.shared.files.FileService;
import com.tirvextech.sms.shared.security.ActorPrincipal;
import com.tirvextech.sms.shared.security.CurrentActor;
import com.tirvextech.sms.shared.tenant.TenantContext;
import com.tirvextech.sms.shared.validation.PakFormats;
import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.web.PageResponse;
import com.tirvextech.sms.shared.year.AcademicYearLookup;
import com.tirvextech.sms.shared.year.AcademicYearLookup.YearRef;
import com.tirvextech.sms.shared.year.YearContext;
import com.tirvextech.sms.students.app.StudentAccess;
import com.tirvextech.sms.students.domain.Student;
import com.tirvextech.sms.students.infra.Enrolments;
import com.tirvextech.sms.students.infra.Enrolments.Enrolment;
import com.tirvextech.sms.students.infra.StudentRepository;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Students (FR 4.7, 5): admission, profile, search, edits, moves and withdrawal. Public API of the students module.
 */
@Service
public class StudentService {

	public static final Set<String> BULK_FIELDS = Set.of("STUDY_GROUP", "DOB", "B_FORM_NO", "RELIGION",
			"NATIONALITY");

	public record FamilySummary(long id, String familyNo, String fatherName, String motherName, String fatherPhone,
			String motherPhone) {
	}

	public record CurrentClass(long enrolmentId, long yearId, String yearName, long classId, String className,
			long sectionId, String sectionName, LocalDate since) {
	}

	/** {@code privateHidden}: phone, address, B-Form and family contacts removed (FR 5.11). */
	public record StudentView(long id, String grNo, StudentDetails details, LocalDate admissionDate, Long firstClassId,
			String status, LocalDate leftDate, FamilySummary family, CurrentClass currentClass, Long enquiryId,
			boolean privateHidden, long version) {
	}

	public record StudentRow(long id, String grNo, String name, String gender, String fatherName, String className,
			String sectionName, Long sectionId, String status, Long photoFileId, String fatherPhone,
			Long familyId) {
	}

	public record Strength(long classId, String className, long sectionId, String sectionName, long total, long boys,
			long girls) {
	}

	public record RegisterRow(long studentId, String grNo, String name, String className, String sectionName,
			String event, LocalDate date, String reason) {
	}

	/**
	 * New admission (FR 4.7, 4.8): an existing family or a new one, the class/section and admission date. The GR number
	 * is always given by the system.
	 */
	public record Admission(StudentDetails details, Long familyId, FamilyDetails newFamily, long sectionId,
			LocalDate admissionDate, Long enquiryId) {
	}

	public record StudentBulkValue(long studentId, String value) {
	}

	private final StudentRepository students;

	private final Enrolments enrolments;

	private final FamilyService families;

	private final AcademicStructure structure;

	private final StudentAccess access;

	private final YearContext yearContext;

	private final AcademicYearLookup years;

	private final FileService files;

	private final AuditService audit;

	private final ApplicationEventPublisher events;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	StudentService(StudentRepository students, Enrolments enrolments, FamilyService families,
			AcademicStructure structure, StudentAccess access, YearContext yearContext, AcademicYearLookup years,
			FileService files, AuditService audit, ApplicationEventPublisher events, JdbcTemplate jdbc, Clock clock) {
		this.students = students;
		this.enrolments = enrolments;
		this.families = families;
		this.structure = structure;
		this.access = access;
		this.yearContext = yearContext;
		this.years = years;
		this.files = files;
		this.audit = audit;
		this.events = events;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// ----------------------------------------------------------------- search

	/**
	 * Find students quickly (FR 5.2): by GR no., name, father's name or phone; by class/section; in the selected year.
	 * {@code status} ACTIVE (default), LEFT (leavers, FR 5.7) or ALL.
	 */
	@Transactional(readOnly = true)
	public PageResponse<StudentRow> search(String q, Long classId, Long sectionId, Long familyId, String status,
			int page, int size) {
		String st = status == null || status.isBlank() ? "ACTIVE" : status;
		YearRef year = yearContext.selected().orElse(null);
		var visible = year == null ? java.util.Optional.<Set<Long>>empty() : access.visibleSections(year);
		if (visible.isPresent() && visible.get().isEmpty()) {
			return PageResponse.of(List.of(), page, size, 0);
		}
		LocalDate on = year == null ? access.today() : access.referenceDate(year);
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> args = new ArrayList<>();
		String text = q == null ? "" : q.trim().toLowerCase();
		if (!text.isEmpty()) {
			String phone = PakFormats.phoneOrNull(q);
			// GR "1" also finds "001"
			where.append(" and (ltrim(lower(s.gr_no), '0') = ltrim(?, '0') or lower(s.name) like ? or lower(coalesce(f.father_name, '')) like ?"
					+ " or f.father_phone = ? or f.mother_phone = ? or s.phone = ?)");
			args.addAll(List.of(text, "%" + text + "%", "%" + text + "%"));
			args.add(phone);
			args.add(phone);
			args.add(phone);
		}
		if (!"ALL".equals(st)) {
			where.append(" and s.status = ?");
			args.add(st);
		}
		if (familyId != null) {
			where.append(" and s.family_id = ?");
			args.add(familyId);
		}
		if (classId != null) {
			where.append(" and e.class_id = ?");
			args.add(classId);
		}
		if (sectionId != null) {
			where.append(" and e.section_id = ?");
			args.add(sectionId);
		}
		if (visible.isPresent()) {
			where.append(" and e.section_id = any(?)");
			args.add(visible.get().toArray(new Long[0]));
		}
		// Class in the selected year on the reference date; leavers show their last class.
		// Leading space kept explicitly: text blocks strip it.
		String from = " " + """
				 from student s
				 join family f on f.id = s.family_id
				 left join lateral (
				   select e1.class_id, e1.section_id from enrolment e1
				   where e1.student_id = s.id and (? ::bigint is null or e1.academic_year_id = ?)
				     and (s.status = 'LEFT' or (e1.effective_from <= ? and (e1.effective_to is null or e1.effective_to >= ?)))
				   order by e1.effective_from desc limit 1) e on true
				 left join school_class c on c.id = e.class_id
				 left join section sec on sec.id = e.section_id""";
		List<Object> fromArgs = new ArrayList<>();
		fromArgs.add(year == null ? null : year.id());
		fromArgs.add(year == null ? null : year.id());
		fromArgs.add(Date.valueOf(on));
		fromArgs.add(Date.valueOf(on));
		if (classId != null || sectionId != null || visible.isPresent()) {
			// filtering by class needs an enrolment
			where.append(" and e.section_id is not null");
		}
		List<Object> countArgs = new ArrayList<>(fromArgs);
		countArgs.addAll(args);
		Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, countArgs.toArray());
		List<Object> pageArgs = new ArrayList<>(countArgs);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		boolean privateOk = access.canSeePrivate();
		List<StudentRow> rows = jdbc.query("""
				select s.id, s.gr_no, s.name, s.gender, f.father_name, c.name, sec.name, e.section_id,
				       s.status, s.photo_file_id, f.father_phone, s.family_id""" + from + where
				+ " order by c.display_order nulls last, sec.name, s.name limit ? offset ?",
				(rs, i) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getString(7), (Long) rs.getObject(8),
						rs.getString(9), (Long) rs.getObject(10), privateOk ? rs.getString(11) : null,
						(Long) rs.getObject(12)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	/** Class strength in the selected year: total, boys, girls per section (FR 5.2). */
	@Transactional(readOnly = true)
	public List<Strength> strength() {
		YearRef year = yearContext.requireSelected();
		LocalDate on = access.referenceDate(year);
		var visible = access.visibleSections(year);
		List<Strength> all = jdbc.query("""
				select c.id, c.name, sec.id, sec.name, count(st.id),
				       count(st.id) filter (where st.gender = 'MALE'), count(st.id) filter (where st.gender = 'FEMALE')
				from section sec join school_class c on c.id = sec.class_id
				left join enrolment e on e.section_id = sec.id and e.academic_year_id = ?
				     and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				left join student st on st.id = e.student_id and st.status = 'ACTIVE'
				where sec.status = 'ACTIVE' and c.status = 'ACTIVE'
				group by c.id, c.name, c.display_order, sec.id, sec.name
				order by c.display_order, sec.name""", (rs, i) -> new Strength(rs.getLong(1), rs.getString(2),
				rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)), year.id(),
				Date.valueOf(on), Date.valueOf(on));
		return visible.map(v -> all.stream().filter(s -> v.contains(s.sectionId())).toList()).orElse(all);
	}

	/** Admissions and withdrawals in a period (FR 3.7, 5.9). */
	@Transactional(readOnly = true)
	public List<RegisterRow> register(LocalDate from, LocalDate to) {
		return jdbc.query("""
				select s.id, s.gr_no, s.name, c.name, sec.name, 'ADMITTED', e.effective_from, null
				from enrolment e join student s on s.id = e.student_id
				join school_class c on c.id = e.class_id join section sec on sec.id = e.section_id
				where e.kind = 'ADMISSION' and e.effective_from between ? and ?
				union all
				select s.id, s.gr_no, s.name, c.name, sec.name, 'LEFT', w.leaving_date, w.reason
				from withdrawal w join student s on s.id = w.student_id
				left join lateral (select class_id, section_id from enrolment where student_id = s.id
				                   order by effective_from desc limit 1) e on true
				left join school_class c on c.id = e.class_id left join section sec on sec.id = e.section_id
				where w.leaving_date between ? and ?
				order by 7 desc""", (rs, i) -> new RegisterRow(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getString(4), rs.getString(5), rs.getString(6), rs.getDate(7).toLocalDate(), rs.getString(8)),
				Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to));
	}

	// ---------------------------------------------------------------- profile

	@Transactional(readOnly = true)
	public StudentView get(long id) {
		Student s = find(id);
		CurrentClass current = currentClass(s);
		YearRef year = yearContext.selected().orElse(null);
		if (year != null) {
			access.checkSection(year, current == null ? null : current.sectionId());
		}
		return view(s, current);
	}

	@Transactional(readOnly = true)
	public List<Enrolment> enrolmentHistory(long id) {
		get(id);
		return enrolments.history(id);
	}

	/** Next free numeric GR number, at least three digits (001, 002 ... 010 ... 100); every admission gets the next one automatically. */
	@Transactional(readOnly = true)
	public String suggestGrNo() {
		Long max = jdbc.queryForObject("""
				select max(gr_no::bigint) from student where gr_no ~ '^[0-9]{1,15}$'""", Long.class);
		return "%03d".formatted(max == null ? 1 : max + 1);
	}

	// ---------------------------------------------------------------- changes

	/** Admits a student in the selected (writable) academic year. */
	@Transactional
	public StudentView admit(Admission a) {
		YearRef year = yearContext.requireWritable();
		var section = structure.requireSection(a.sectionId());
		if (!section.active()) {
			throw ApiException.businessRule("SECTION_INACTIVE", section.label() + " is not active");
		}
		LocalDate date = a.admissionDate() == null ? access.today() : a.admissionDate();
		if (date.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The admission date is after the end of " + year.name());
		}
		// Serialise admissions of this school, so two at the same moment never get the same GR number.
		jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('gr-no'), ?::int)", Object.class,
				TenantContext.requireSchoolId().intValue());
		String grNo = suggestGrNo();
		long familyId;
		if (a.familyId() != null) {
			if (!families.exists(a.familyId())) {
				throw ApiException.notFound("Family");
			}
			familyId = a.familyId();
		}
		else if (a.newFamily() != null) {
			familyId = families.create(a.newFamily()).id();
		}
		else {
			throw ApiException.businessRule("FAMILY_REQUIRED", "Choose an existing family or enter the parents' details");
		}
		Student student = new Student(grNo, familyId, date, section.classId(), a.enquiryId());
		student.apply(validate(a.details()), clock.instant());
		student = students.save(student);
		LocalDate from = date.isBefore(year.startDate()) ? year.startDate() : date;
		enrolments.create(student.getId(), year.id(), section.classId(), section.id(), from, null, "ADMISSION", null);
		audit.record("STUDENT_ADMITTED", "student", student.getId(), null,
				Map.of("grNo", grNo, "name", student.getName(), "section", section.label(), "date", date.toString()));
		events.publishEvent(new StudentEvents.Admitted(student.getId(), year.id(), section.classId(), date));
		return view(student, currentClass(student));
	}

	@Transactional
	public StudentView update(long id, StudentDetails input, long expectedVersion) {
		Student s = find(id);
		guardEdit(s);
		if (s.getVersion() != expectedVersion) {
			throw ApiException.conflict("CONFLICT_VERSION", "The student was changed by someone else. Reload and try again.");
		}
		StudentDetails before = s.details();
		StudentDetails incoming = input;
		if (!access.canSeePrivate()) {
			// Private fields were hidden from this editor: keep the stored values.
			incoming = new StudentDetails(input.name(), input.gender(), input.dob(), input.placeOfBirth(), before.bFormNo(),
					input.religion(), input.nationality(), before.phone(), before.address(), input.previousSchool(),
					input.studyGroup(), input.photoFileId(), input.notes());
		}
		s.apply(validate(incoming), clock.instant());
		students.flush();
		audit.record("STUDENT_UPDATED", "student", id, before, s.details());
		return view(s, currentClass(s));
	}

	/** Links the student to another family; the client must confirm (FR 8.4). */
	@Transactional
	public StudentView changeFamily(long id, long familyId) {
		Student s = find(id);
		guardEdit(s);
		if (!families.exists(familyId)) {
			throw ApiException.notFound("Family");
		}
		long before = s.getFamilyId();
		s.changeFamily(familyId, clock.instant());
		audit.record("STUDENT_FAMILY_CHANGED", "student", id, Map.of("familyId", before), Map.of("familyId", familyId));
		return view(s, currentClass(s));
	}

	/** One field for many students at once, e.g. study groups for Class 10 (FR 5.3). */
	@Transactional
	public int bulkUpdate(String field, List<StudentBulkValue> values) {
		if (!BULK_FIELDS.contains(field)) {
			throw ApiException.businessRule("FIELD_NOT_ALLOWED", "This field cannot be changed in bulk");
		}
		int count = 0;
		for (StudentBulkValue v : values) {
			Student s = find(v.studentId());
			guardEdit(s);
			String value = PakFormats.trimToNull(v.value());
			StudentDetails d = s.details();
			StudentDetails changed = switch (field) {
				case "STUDY_GROUP" -> copy(d, value, null, null, null, null);
				case "DOB" -> copy(d, null, value == null ? null : LocalDate.parse(value), null, null, null);
				case "B_FORM_NO" -> copy(d, null, null, PakFormats.cnic(value, s.getName()), null, null);
				case "RELIGION" -> copy(d, null, null, null, value, null);
				default -> copy(d, null, null, null, null, value);
			};
			s.apply(changed, clock.instant());
			audit.record("STUDENT_BULK_UPDATED", "student", s.getId(), d, changed, field);
			count++;
		}
		return count;
	}

	/**
	 * Moves the student to another section/class from a date (FR 5.4). Before the date the old class keeps the
	 * student's attendance and results; from the date the new class does.
	 */
	@Transactional
	public StudentView move(long id, long toSectionId, LocalDate effectiveDate, String reason) {
		Student s = find(id);
		guardEdit(s);
		YearRef year = yearContext.requireWritable();
		var target = structure.requireSection(toSectionId);
		if (!target.active()) {
			throw ApiException.businessRule("SECTION_INACTIVE", target.label() + " is not active");
		}
		LocalDate date = effectiveDate == null ? access.today() : effectiveDate;
		if (date.isBefore(year.startDate()) || date.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The date must be within " + year.name());
		}
		Enrolment current = enrolments.on(s.getId(), date)
			.filter(e -> e.yearId() == year.id())
			.orElseThrow(() -> ApiException.businessRule("NOT_ENROLLED", "The student is not in a class on that date"));
		if (current.sectionId() == toSectionId) {
			throw ApiException.businessRule("SAME_SECTION", "The student is already in " + target.label());
		}
		if (current.effectiveTo() != null) {
			throw ApiException.businessRule("LATER_CHANGES", "The student has later class changes; change those first");
		}
		String why = PakFormats.trimToNull(reason);
		if (current.effectiveFrom().equals(date)) {
			enrolments.changeSection(current.id(), target.classId(), toSectionId, why);
		}
		else {
			enrolments.close(current.id(), date.minusDays(1));
			enrolments.create(s.getId(), year.id(), target.classId(), toSectionId, date, null, "MOVE", why);
		}
		audit.record("STUDENT_MOVED", "student", id,
				Map.of("section", current.className() + " - " + current.sectionName()),
				Map.of("section", target.label(), "from", date.toString()), why);
		return view(s, currentClass(s));
	}

	/** Records that the student left (FR 5.6). Unpaid fees are checked from Phase 3 onwards. */
	@Transactional
	public StudentView withdraw(long id, LocalDate leavingDate, String reason) {
		Student s = find(id);
		if (!"ACTIVE".equals(s.getStatus())) {
			throw ApiException.conflict("ALREADY_LEFT", "This student has already left");
		}
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give the reason for leaving");
		}
		LocalDate date = leavingDate == null ? access.today() : leavingDate;
		if (date.isBefore(s.getAdmissionDate())) {
			throw ApiException.businessRule("DATE_INVALID", "The leaving date is before the admission date");
		}
		Enrolment current = enrolments.on(s.getId(), date).orElse(null);
		YearRef year = current == null ? yearContext.requireWritable()
				: years.findById(current.yearId()).orElseThrow();
		yearContext.requireWritable(year);
		if (current != null) {
			if (current.effectiveTo() != null && current.effectiveTo().isAfter(date)) {
				throw ApiException.businessRule("LATER_CHANGES", "The student has later class changes");
			}
			enrolments.close(current.id(), date);
		}
		// Remove enrolments that start after the leaving date (e.g. already promoted into next year).
		jdbc.update("delete from enrolment where student_id = ? and effective_from > ?", id, Date.valueOf(date));
		s.withdraw(date, clock.instant());
		ActorPrincipal actor = CurrentActor.require();
		jdbc.update("""
				insert into withdrawal (school_id, student_id, academic_year_id, leaving_date, reason, recorded_by_name)
				values (?, ?, ?, ?, ?, ?)""", TenantContext.requireSchoolId(), id, year.id(), Date.valueOf(date), why,
				actor.displayName());
		audit.record("STUDENT_WITHDRAWN", "student", id, null, Map.of("leavingDate", date.toString()), why);
		events.publishEvent(new StudentEvents.Withdrawn(id, date, why));
		return view(s, null);
	}

	/** Re-admits a former student into a section of the selected year, keeping GR number and history. */
	@Transactional
	public StudentView readmit(long id, long sectionId, LocalDate date, String reason) {
		Student s = find(id);
		if (!"LEFT".equals(s.getStatus())) {
			throw ApiException.conflict("NOT_LEFT", "The student is still active");
		}
		YearRef year = yearContext.requireWritable();
		var section = structure.requireSection(sectionId);
		LocalDate from = date == null ? access.today() : date;
		if (from.isBefore(year.startDate()) || from.isAfter(year.endDate()) || !from.isAfter(s.getLeftDate())) {
			throw ApiException.businessRule("DATE_INVALID", "The date must be in " + year.name() + " and after leaving");
		}
		s.readmit(clock.instant());
		enrolments.create(id, year.id(), section.classId(), sectionId, from, null, "ADMISSION",
				PakFormats.trimToNull(reason));
		audit.record("STUDENT_READMITTED", "student", id, null, Map.of("section", section.label(), "date", from.toString()),
				reason);
		return view(s, currentClass(s));
	}

	// ------------------------------------------------------------ module API

	/** The reason recorded at the latest withdrawal (for the leaving certificate). */
	@Transactional(readOnly = true)
	public java.util.Optional<String> leavingReason(long id) {
		return jdbc.queryForList("select reason from withdrawal where student_id = ? order by leaving_date desc, id desc limit 1",
				String.class, id).stream().findFirst();
	}

	@Transactional(readOnly = true)
	public Student entity(long id) {
		return find(id);
	}

	/** Students of the selected year's sections, for promotion and ID cards. */
	@Transactional(readOnly = true)
	public List<Long> idsInSection(long sectionId, YearRef year) {
		LocalDate on = access.referenceDate(year);
		return jdbc.queryForList("""
				select e.student_id from enrolment e join student s on s.id = e.student_id
				where e.section_id = ? and e.academic_year_id = ? and s.status = 'ACTIVE'
				  and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				order by s.name""", Long.class, sectionId, year.id(), Date.valueOf(on), Date.valueOf(on));
	}

	public CurrentClass currentClass(Student s) {
		YearRef year = yearContext.selected().orElse(null);
		if (year == null) {
			return null;
		}
		LocalDate on = "LEFT".equals(s.getStatus()) && s.getLeftDate() != null ? s.getLeftDate()
				: access.referenceDate(year);
		var e = enrolments.on(s.getId(), on).or(() -> enrolments.latestInYear(s.getId(), year.id()));
		return e.map(x -> new CurrentClass(x.id(), x.yearId(), x.yearName(), x.classId(), x.className(),
				x.sectionId(), x.sectionName(), x.effectiveFrom())).orElse(null);
	}

	private void guardEdit(Student s) {
		YearRef year = yearContext.selected().orElse(null);
		if (year != null) {
			CurrentClass current = currentClass(s);
			access.checkSection(year, current == null ? null : current.sectionId());
		}
	}

	private Student find(long id) {
		return students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
	}

	private StudentView view(Student s, CurrentClass current) {
		boolean privateOk = access.canSeePrivate();
		var family = families.get(s.getFamilyId());
		var fd = family.details();
		FamilySummary fs = new FamilySummary(family.id(), family.familyNo(), fd.fatherName(), fd.motherName(),
				privateOk ? fd.fatherPhone() : null, privateOk ? fd.motherPhone() : null);
		StudentDetails d = s.details();
		StudentDetails shown = privateOk ? d
				: new StudentDetails(d.name(), d.gender(), d.dob(), d.placeOfBirth(), null, d.religion(), d.nationality(),
						null, null, d.previousSchool(), d.studyGroup(), d.photoFileId(), d.notes());
		return new StudentView(s.getId(), s.getGrNo(), shown, s.getAdmissionDate(), s.getFirstClassId(), s.getStatus(),
				s.getLeftDate(), fs, current, s.getEnquiryId(), !privateOk, s.getVersion());
	}

	private StudentDetails validate(StudentDetails d) {
		if (PakFormats.trimToNull(d.name()) == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Student name is required");
		}
		if (d.gender() == null || !Set.of("MALE", "FEMALE").contains(d.gender())) {
			throw ApiException.businessRule("GENDER_REQUIRED", "Choose boy or girl");
		}
		if (d.dob() != null && d.dob().isAfter(access.today())) {
			throw ApiException.businessRule("DOB_INVALID", "Date of birth cannot be in the future");
		}
		if (d.photoFileId() != null) {
			var photo = files.find(d.photoFileId())
				.orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "Photo not found"));
			if (!"PHOTO".equals(photo.purpose())) {
				throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "Upload the picture as a photo");
			}
		}
		return new StudentDetails(d.name().trim(), d.gender(), d.dob(), PakFormats.trimToNull(d.placeOfBirth()),
				PakFormats.cnic(d.bFormNo(), "B-Form number"), PakFormats.trimToNull(d.religion()),
				PakFormats.trimToNull(d.nationality()), PakFormats.phone(d.phone(), "Phone"),
				PakFormats.trimToNull(d.address()), PakFormats.trimToNull(d.previousSchool()),
				PakFormats.trimToNull(d.studyGroup()), d.photoFileId(),
				PakFormats.trimToNull(d.notes()));
	}

	private static StudentDetails copy(StudentDetails d, String studyGroup, LocalDate dob, String bForm, String religion,
			String nationality) {
		return new StudentDetails(d.name(), d.gender(), dob != null ? dob : d.dob(), d.placeOfBirth(),
				bForm != null ? bForm : d.bFormNo(), religion != null ? religion : d.religion(),
				nationality != null ? nationality : d.nationality(), d.phone(), d.address(), d.previousSchool(),
				studyGroup != null ? studyGroup : d.studyGroup(), d.photoFileId(), d.notes());
	}
}
