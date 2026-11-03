package com.selectcode.sms.admissions.app;

import com.selectcode.sms.students.StudentDetails;
import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.admissions.domain.Enquiry;
import com.selectcode.sms.admissions.domain.Enquiry.EnquiryDetails;
import com.selectcode.sms.admissions.domain.Enquiry.TestResult;
import com.selectcode.sms.admissions.infra.EnquiryRepository;
import com.selectcode.sms.families.FamilyService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.students.StudentService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission enquiries from first contact to enrolment (FR 4). */
@Service
public class AdmissionsService {

	private static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	public record EnquiryView(long id, String registrationNo, EnquiryDetails details, String className,
			EnquiryStatuses.EnquiryStatus status, TestResult test, Long studentId, Instant createdAt, boolean contactsHidden,
			long version) {
	}

	public record EnquiryRow(long id, String registrationNo, LocalDate enquiryDate, String childName,
			String className, String parentName, String parentPhone, long statusId, String statusName,
			String statusColour, String source, BigDecimal testObtained, BigDecimal testTotal, long remarks,
			LocalDate lastContact) {
	}

	public record Remark(long id, String text, String byName, Instant at) {
	}

	public record Match(String kind, long id, String label, String detail, String reason) {
	}

	/** Enrolling an approved enquiry: the admission form is pre-filled from it (FR 4.6, 4.7). */
	public record EnrolmentInput(String grNo, StudentDetails student, Long familyId, FamilyDetails newFamily,
			long sectionId, LocalDate admissionDate, String rollNo) {
	}

	private final EnquiryRepository enquiries;

	private final EnquiryStatuses statuses;

	private final NumberSequences sequences;

	private final AcademicStructure structure;

	private final StudentService students;

	private final FamilyService families;

	private final YearContext yearContext;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	AdmissionsService(EnquiryRepository enquiries, EnquiryStatuses statuses, NumberSequences sequences,
			AcademicStructure structure, StudentService students, FamilyService families, YearContext yearContext,
			AuditService audit, JdbcTemplate jdbc, Clock clock) {
		this.enquiries = enquiries;
		this.statuses = statuses;
		this.sequences = sequences;
		this.structure = structure;
		this.students = students;
		this.families = families;
		this.yearContext = yearContext;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<EnquiryRow> search(String q, Long statusId, Long classId, LocalDate from, LocalDate to,
			int page, int size) {
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> args = new ArrayList<>();
		if (q != null && !q.isBlank()) {
			String text = q.trim().toLowerCase();
			where.append(" and (lower(e.child_name) like ? or lower(coalesce(e.parent_name, '')) like ?"
					+ " or lower(e.registration_no) = ? or e.parent_phone = ?)");
			args.addAll(List.of("%" + text + "%", "%" + text + "%", text));
			args.add(PakFormats.phoneOrNull(q));
		}
		if (statusId != null) {
			where.append(" and e.status_id = ?");
			args.add(statusId);
		}
		if (classId != null) {
			where.append(" and e.class_applied_id = ?");
			args.add(classId);
		}
		if (from != null) {
			where.append(" and e.enquiry_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and e.enquiry_date <= ?");
			args.add(Date.valueOf(to));
		}
		Long total = jdbc.queryForObject("select count(*) from enquiry e" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		boolean contacts = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW)
				|| CurrentActor.hasPermission(Permissions.ADMISSIONS_MANAGE);
		List<EnquiryRow> rows = jdbc.query("""
				select e.id, e.registration_no, e.enquiry_date, e.child_name, c.name, e.parent_name, e.parent_phone,
				       st.id, st.name, st.colour, e.source, e.test_obtained, e.test_total,
				       (select count(*) from enquiry_remark r where r.enquiry_id = e.id),
				       coalesce((select max(r.at)::date from enquiry_remark r where r.enquiry_id = e.id), e.enquiry_date)
				from enquiry e join enquiry_status st on st.id = e.status_id
				left join school_class c on c.id = e.class_applied_id""" + where
				+ " order by e.enquiry_date desc, e.id desc limit ? offset ?",
				(rs, i) -> new EnquiryRow(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4),
						rs.getString(5), rs.getString(6), contacts ? rs.getString(7) : null, rs.getLong(8),
						rs.getString(9), rs.getString(10), rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13),
						rs.getLong(14), rs.getDate(15).toLocalDate()),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public EnquiryView get(long id) {
		return view(find(id));
	}

	/** New enquiry (FR 4.1): walk-in, phone or online; status "New enquiry". */
	@Transactional
	public EnquiryView create(EnquiryDetails input) {
		EnquiryDetails clean = validate(input);
		Enquiry enquiry = new Enquiry(NumberSequences.format("ENQ", sequences.next("enquiry"), 5),
				statuses.bySystemCode(EnquiryStatuses.NEW).id());
		enquiry.apply(clean, clock.instant());
		enquiry = enquiries.save(enquiry);
		audit.record("ENQUIRY_CREATED", "enquiry", enquiry.getId(), null,
				Map.of("registrationNo", enquiry.getRegistrationNo(), "child", clean.childName()));
		return view(enquiry);
	}

	@Transactional
	public EnquiryView update(long id, EnquiryDetails input, long expectedVersion) {
		Enquiry e = find(id);
		guardOpen(e);
		if (e.getVersion() != expectedVersion) {
			throw ApiException.conflict("CONFLICT_VERSION", "The enquiry was changed by someone else. Reload and try again.");
		}
		EnquiryDetails before = e.details();
		e.apply(validate(input), clock.instant());
		enquiries.flush();
		audit.record("ENQUIRY_UPDATED", "enquiry", id, before, e.details());
		return view(e);
	}

	@Transactional
	public EnquiryView changeStatus(long id, long statusId, String remark) {
		Enquiry e = find(id);
		guardOpen(e);
		var target = statuses.require(statusId);
		if (EnquiryStatuses.ENROLLED.equals(target.systemCode())) {
			throw ApiException.businessRule("USE_ENROL", "Use Enrol to create the student record");
		}
		if (!target.active()) {
			throw ApiException.businessRule("STATUS_INACTIVE", "This status is switched off");
		}
		var before = statuses.require(e.getStatusId());
		e.changeStatus(statusId, clock.instant());
		if (remark != null && !remark.isBlank()) {
			addRemark(id, remark);
		}
		audit.record("ENQUIRY_STATUS_CHANGED", "enquiry", id, Map.of("status", before.name()),
				Map.of("status", target.name()), remark);
		return view(e);
	}

	@Transactional
	public EnquiryView setTest(long id, TestResult result) {
		Enquiry e = find(id);
		guardOpen(e);
		if (result.obtained() != null && result.total() != null
				&& (result.obtained().signum() < 0 || result.obtained().compareTo(result.total()) > 0)) {
			throw ApiException.businessRule("MARKS_INVALID", "Obtained marks must be between 0 and the total");
		}
		if (result.total() != null && result.total().signum() <= 0) {
			throw ApiException.businessRule("MARKS_INVALID", "Total marks must be more than 0");
		}
		TestResult before = e.test();
		e.setTest(new TestResult(result.testDate(), result.obtained(), result.total(),
				PakFormats.trimToNull(result.remark())), clock.instant());
		audit.record("ENQUIRY_TEST_RECORDED", "enquiry", id, before, e.test());
		return view(e);
	}

	// --------------------------------------------------------------- remarks

	@Transactional(readOnly = true)
	public List<Remark> remarks(long enquiryId) {
		find(enquiryId);
		return jdbc.query("select id, text, by_name, at from enquiry_remark where enquiry_id = ? order by at desc, id desc",
				(rs, i) -> new Remark(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
				enquiryId);
	}

	/** Follow-up history: any staff member can read what was said to the family (FR 4.3). */
	@Transactional
	public Remark addRemark(long enquiryId, String text) {
		find(enquiryId);
		String clean = PakFormats.trimToNull(text);
		if (clean == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Write the remark");
		}
		String by = CurrentActor.require().displayName();
		Long id = jdbc.queryForObject("""
				insert into enquiry_remark (school_id, enquiry_id, text, by_name) values (?, ?, ?, ?) returning id""",
				Long.class, TenantContext.requireSchoolId(), enquiryId, clean, by);
		return new Remark(id, clean, by, clock.instant());
	}

	// ------------------------------------------------------------ duplicates

	/**
	 * Possible existing enquiries, students and families for the typed details (FR 4.5): same phone, CNIC/B-Form, or
	 * a similar name with the same date of birth.
	 */
	@Transactional(readOnly = true)
	public List<Match> duplicates(String childName, LocalDate dob, String phone, String cnic, Long excludeEnquiryId) {
		String p = PakFormats.phoneOrNull(phone);
		String c = PakFormats.cnicOrNull(cnic);
		String name = childName == null ? "" : childName.trim().toLowerCase();
		List<Match> matches = new ArrayList<>();
		if (name.length() >= 3 || p != null || c != null) {
			jdbc.query("""
					select e.id, e.registration_no, e.child_name, e.enquiry_date, e.parent_phone = ? as same_phone,
					       e.parent_cnic = ? as same_cnic, e.dob = ? as same_dob, similarity(lower(e.child_name), ?) as sim
					from enquiry e
					where (e.id <> coalesce(?, -1))
					  and (e.parent_phone = ? or e.parent_cnic = ?
					       or (similarity(lower(e.child_name), ?) > 0.45 and (? ::date is null or e.dob = ?)))
					order by e.enquiry_date desc limit 10""", rs -> {
				matches.add(new Match("ENQUIRY", rs.getLong(1), rs.getString(3) + " (" + rs.getString(2) + ")",
						"Enquiry of " + rs.getDate(4).toLocalDate(),
						rs.getBoolean(5) ? "Same parent phone" : rs.getBoolean(6) ? "Same CNIC" : "Similar name"));
			}, p, c, dob == null ? null : Date.valueOf(dob), name, excludeEnquiryId, p, c, name,
					dob == null ? null : Date.valueOf(dob), dob == null ? null : Date.valueOf(dob));
			jdbc.query("""
					select s.id, s.gr_no, s.name, s.status, f.father_phone = ? or f.mother_phone = ? as same_phone,
					       s.b_form_no = ? as same_bform
					from student s join family f on f.id = s.family_id
					where f.father_phone = ? or f.mother_phone = ? or s.b_form_no = ? or f.father_cnic = ?
					   or (similarity(lower(s.name), ?) > 0.45 and (? ::date is null or s.dob = ?))
					order by s.name limit 10""", rs -> {
				matches.add(new Match("STUDENT", rs.getLong(1), rs.getString(3) + " (GR " + rs.getString(2) + ")",
						"LEFT".equals(rs.getString(4)) ? "Former student" : "Current student",
						rs.getBoolean(5) ? "Same family phone" : rs.getBoolean(6) ? "Same B-Form" : "Similar name"));
			}, p, p, c, p, p, c, c, name, dob == null ? null : Date.valueOf(dob), dob == null ? null : Date.valueOf(dob));
		}
		for (var f : families.findByContact(phone, cnic)) {
			matches.add(new Match("FAMILY", f.id(), f.familyNo() + " · " + (f.fatherName() == null ? "" : f.fatherName()),
					f.children() + " child(ren) at school", "Same phone or CNIC"));
		}
		return matches;
	}

	// ---------------------------------------------------------------- enrol

	/** Creates the student from an approved enquiry and marks it enrolled (FR 4.6), in one transaction. */
	@Transactional
	public StudentService.StudentView enrol(long enquiryId, EnrolmentInput in) {
		Enquiry e = find(enquiryId);
		guardOpen(e);
		var status = statuses.require(e.getStatusId());
		if (!EnquiryStatuses.APPROVED.equals(status.systemCode())) {
			throw ApiException.businessRule("NOT_APPROVED", "Approve the admission before enrolling");
		}
		var created = students.admit(new StudentService.Admission(in.grNo(), in.student(), in.familyId(),
				in.newFamily(), in.sectionId(), in.admissionDate(), in.rollNo(), enquiryId));
		e.enrolled(created.id(), statuses.bySystemCode(EnquiryStatuses.ENROLLED).id(), clock.instant());
		audit.record("ENQUIRY_ENROLLED", "enquiry", enquiryId, null,
				Map.of("studentId", created.id(), "grNo", created.grNo()));
		return created;
	}

	// ---------------------------------------------------------------- helpers

	Enquiry find(long id) {
		return enquiries.findById(id).orElseThrow(() -> ApiException.notFound("Enquiry"));
	}

	private void guardOpen(Enquiry e) {
		if (e.getStudentId() != null) {
			throw ApiException.conflict("ALREADY_ENROLLED", "This enquiry is already enrolled; edit the student instead");
		}
	}

	private EnquiryView view(Enquiry e) {
		EnquiryDetails d = e.details();
		String className = d.classAppliedId() == null ? null
				: structure.findClass(d.classAppliedId()).map(AcademicStructure.ClassRef::name).orElse(null);
		return new EnquiryView(e.getId(), e.getRegistrationNo(), d, className, statuses.require(e.getStatusId()),
				e.test(), e.getStudentId(), e.getCreatedAt(), false, e.getVersion());
	}

	private EnquiryDetails validate(EnquiryDetails d) {
		String child = PakFormats.trimToNull(d.childName());
		if (child == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Enter the child's name");
		}
		String source = d.source() == null ? "WALK_IN" : d.source();
		if (!Set.of("WALK_IN", "PHONE", "ONLINE").contains(source)) {
			throw ApiException.businessRule("SOURCE_INVALID", "Unknown source");
		}
		if (d.gender() != null && !Set.of("MALE", "FEMALE").contains(d.gender())) {
			throw ApiException.businessRule("GENDER_INVALID", "Unknown gender");
		}
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		if (d.dob() != null && d.dob().isAfter(today)) {
			throw ApiException.businessRule("DOB_INVALID", "Date of birth cannot be in the future");
		}
		if (d.classAppliedId() != null) {
			structure.requireClass(d.classAppliedId());
		}
		Long yearId = d.academicYearId() != null ? d.academicYearId()
				: yearContext.selected().map(y -> y.id()).orElse(null);
		return new EnquiryDetails(d.enquiryDate() == null ? today : d.enquiryDate(), source, child, d.gender(), d.dob(),
				d.classAppliedId(), yearId, PakFormats.trimToNull(d.parentName()),
				PakFormats.phone(d.parentPhone(), "Parent phone"), PakFormats.cnic(d.parentCnic(), "Parent CNIC"),
				PakFormats.trimToNull(d.address()), PakFormats.trimToNull(d.previousSchool()),
				PakFormats.trimToNull(d.remarks()));
	}
}
