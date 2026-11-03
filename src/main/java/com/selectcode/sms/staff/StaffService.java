package com.selectcode.sms.staff;

import com.selectcode.sms.iam.SchoolUserAdministration;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.events.StaffLeftEvent;
import com.selectcode.sms.shared.files.FileService;
import com.selectcode.sms.shared.money.Money;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.ActorPrincipal;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.DataScope;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import com.selectcode.sms.staff.domain.Staff;
import com.selectcode.sms.staff.domain.Staff.StaffDetails;
import com.selectcode.sms.staff.infra.StaffRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teachers and other staff (FR 6, 7), their documents, salary details (FR 6.8) and logins (FR 6.2, 7.2). */
@Service
public class StaffService {

	public static final Set<String> CATEGORIES = Set.of("TEACHER", "OTHER");

	public record StaffView(long id, String employeeNo, StaffDetails details, String status, LocalDate leavingDate,
			String leavingReason, SchoolUserAdministration.LoginRef login, long version) {
	}

	public record StaffRow(long id, String employeeNo, String name, String category, String designation,
			String phone, String status, LocalDate joiningDate) {
	}

	public record StaffRef(long id, String employeeNo, String name, String category, boolean active) {
	}

	public record StaffDocumentView(long id, String docType, String title, long fileId) {
	}

	public record SalaryEntry(long id, LocalDate effectiveFrom, String basicSalary, String perLectureRate,
			String bankName, String bankAccount, String reason, String createdBy) {
	}

	public record SalaryInput(LocalDate effectiveFrom, String basicSalary, String perLectureRate, String bankName,
			String bankAccount, String reason) {
	}

	private final StaffRepository staff;

	private final NumberSequences sequences;

	private final SchoolUserAdministration logins;

	private final FileService files;

	private final AuditService audit;

	private final ApplicationEventPublisher events;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	StaffService(StaffRepository staff, NumberSequences sequences, SchoolUserAdministration logins, FileService files,
			AuditService audit, ApplicationEventPublisher events, JdbcTemplate jdbc, Clock clock) {
		this.staff = staff;
		this.sequences = sequences;
		this.logins = logins;
		this.files = files;
		this.audit = audit;
		this.events = events;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<StaffRow> search(String q, String category, String status, int page, int size) {
		Collection<String> categories = category == null || category.isBlank() ? CATEGORIES : Set.of(category);
		Collection<String> statuses = status == null || status.isBlank() ? Set.of("ACTIVE", "LEFT") : Set.of(status);
		var result = staff.search(q == null ? "" : q.trim().toLowerCase(), categories, statuses,
				PageRequest.of(page, size));
		return PageResponse.of(result, s -> {
			var d = s.details();
			return new StaffRow(s.getId(), s.getEmployeeNo(), s.getName(), s.getCategory(), s.getDesignation(),
					d.phone(), s.getStatus(), s.getJoiningDate());
		});
	}

	@Transactional(readOnly = true)
	public StaffView get(long id) {
		return view(find(id));
	}

	@Transactional
	public StaffView create(StaffDetails input) {
		StaffDetails clean = validate(input);
		Staff member = new Staff(NumberSequences.format("EMP", sequences.next("employee"), 4));
		member.apply(clean, clock.instant());
		member = staff.save(member);
		audit.record("STAFF_CREATED", "staff", member.getId(), null, member.details());
		return view(member);
	}

	@Transactional
	public StaffView update(long id, StaffDetails input, long expectedVersion) {
		Staff member = find(id);
		if (member.getVersion() != expectedVersion) {
			throw ApiException.conflict("CONFLICT_VERSION", "The record was changed by someone else. Reload and try again.");
		}
		StaffDetails before = member.details();
		member.apply(validate(input), clock.instant());
		staff.flush();
		audit.record("STAFF_UPDATED", "staff", id, before, member.details());
		return view(member);
	}

	/**
	 * Records that the staff member left (FR 6.7, 7.4): login deactivated, teaching assignments and timetable ended
	 * from the leaving date; attendance and salary history stay.
	 */
	@Transactional
	public StaffView leave(long id, LocalDate date, String reason) {
		Staff member = find(id);
		if (!"ACTIVE".equals(member.getStatus())) {
			throw ApiException.conflict("ALREADY_LEFT", "This staff member has already left");
		}
		if (date == null || date.isBefore(member.getJoiningDate())) {
			throw ApiException.businessRule("DATE_INVALID", "The leaving date cannot be before the joining date");
		}
		String why = PakFormats.trimToNull(reason);
		if (why == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give a reason");
		}
		member.leave(date, why, clock.instant());
		logins.deactivateLoginOfStaff(id, "Left the school: " + why);
		events.publishEvent(new StaffLeftEvent(id, date));
		audit.record("STAFF_LEFT", "staff", id, null, Map.of("leavingDate", date.toString(), "reason", why), why);
		return view(member);
	}

	@Transactional
	public StaffView rejoin(long id) {
		Staff member = find(id);
		member.rejoin(clock.instant());
		audit.record("STAFF_REJOINED", "staff", id, null, null);
		return view(member);
	}

	/** Login for the staff member, with the roles chosen (teachers see only their classes by default). */
	@Transactional
	public SchoolUserAdministration.NewLogin createLogin(long id, String username, List<Long> roleIds,
			DataScope scope) {
		Staff member = find(id);
		if (!"ACTIVE".equals(member.getStatus())) {
			throw ApiException.conflict("STAFF_LEFT", "This staff member has left");
		}
		var d = member.details();
		DataScope effective = scope != null ? scope
				: "TEACHER".equals(member.getCategory()) ? DataScope.ASSIGNED_CLASSES : DataScope.ALL;
		return logins.createLoginForStaff(id, username, member.getName(), d.email(), d.phone(), roleIds, effective);
	}

	// ------------------------------------------------------------- documents

	@Transactional(readOnly = true)
	public List<StaffDocumentView> documents(long staffId) {
		find(staffId);
		return jdbc.query("select id, doc_type, title, file_id from staff_document where staff_id = ? order by id",
				(rs, i) -> new StaffDocumentView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), staffId);
	}

	@Transactional
	public StaffDocumentView addDocument(long staffId, String docType, String title, long fileId) {
		find(staffId);
		var file = files.find(fileId).orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "File not found"));
		if (!"STAFF_DOCUMENT".equals(file.purpose())) {
			throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "Upload the file as a staff document");
		}
		String cleanTitle = PakFormats.trimToNull(title) == null ? file.originalName() : title.trim();
		Long id = jdbc.queryForObject("""
				insert into staff_document (school_id, staff_id, doc_type, title, file_id)
				values (?, ?, ?, ?, ?) returning id""", Long.class,
				com.selectcode.sms.shared.tenant.TenantContext.requireSchoolId(), staffId,
				docType == null ? "OTHER" : docType, cleanTitle, fileId);
		audit.record("STAFF_DOCUMENT_ADDED", "staff", staffId, null, Map.of("title", cleanTitle));
		return new StaffDocumentView(id, docType, cleanTitle, fileId);
	}

	@Transactional
	public void removeDocument(long staffId, long documentId) {
		int removed = jdbc.update("delete from staff_document where id = ? and staff_id = ?", documentId, staffId);
		if (removed == 0) {
			throw ApiException.notFound("Document");
		}
		audit.record("STAFF_DOCUMENT_REMOVED", "staff", staffId, Map.of("documentId", documentId), null);
	}

	// ---------------------------------------------------------------- salary

	@Transactional(readOnly = true)
	public List<SalaryEntry> salaryHistory(long staffId) {
		find(staffId);
		return jdbc.query("""
				select id, effective_from, basic_salary, per_lecture_rate, bank_name, bank_account, reason, created_by_name
				from salary_profile where staff_id = ? order by effective_from desc""",
				(rs, i) -> new SalaryEntry(rs.getLong(1), rs.getDate(2).toLocalDate(), money(rs.getBigDecimal(3)),
						money(rs.getBigDecimal(4)), rs.getString(5), rs.getString(6), rs.getString(7),
						rs.getString(8)),
				staffId);
	}

	/** Starting salary or an increment (FR 6.8): a new history row; payroll uses the row in force for the month. */
	@Transactional
	public SalaryEntry addSalary(long staffId, SalaryInput input) {
		find(staffId);
		if (input.effectiveFrom() == null) {
			throw ApiException.businessRule("DATE_REQUIRED", "Enter the date the salary applies from");
		}
		Money basic = parseMoney(input.basicSalary(), "Basic salary");
		Money perLecture = input.perLectureRate() == null || input.perLectureRate().isBlank() ? null
				: parseMoney(input.perLectureRate(), "Per-lecture rate");
		String reason = PakFormats.trimToNull(input.reason());
		if (reason == null) {
			throw ApiException.businessRule("REASON_REQUIRED", "Give a reason, e.g. starting salary or annual increment");
		}
		ActorPrincipal actor = CurrentActor.require();
		Long id = jdbc.queryForObject("""
				insert into salary_profile (school_id, staff_id, effective_from, basic_salary, per_lecture_rate,
				                            bank_name, bank_account, reason, created_by_name)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				com.selectcode.sms.shared.tenant.TenantContext.requireSchoolId(), staffId,
				Date.valueOf(input.effectiveFrom()), basic.amount(), perLecture == null ? null : perLecture.amount(),
				PakFormats.trimToNull(input.bankName()), PakFormats.trimToNull(input.bankAccount()), reason,
				actor.displayName());
		audit.record("SALARY_SET", "staff", staffId, null,
				Map.of("effectiveFrom", input.effectiveFrom().toString(), "basicSalary", basic.toString()), reason);
		return new SalaryEntry(id, input.effectiveFrom(), basic.toString(),
				perLecture == null ? null : perLecture.toString(), input.bankName(), input.bankAccount(), reason,
				actor.displayName());
	}

	// ---------------------------------------------------------- module API

	@Transactional(readOnly = true)
	public StaffRef ref(long id) {
		Staff s = find(id);
		return new StaffRef(s.getId(), s.getEmployeeNo(), s.getName(), s.getCategory(), "ACTIVE".equals(s.getStatus()));
	}

	/** Entity for certificate printing within the module. */
	Staff entity(long id) {
		return find(id);
	}

	private Staff find(long id) {
		return staff.findById(id).orElseThrow(() -> ApiException.notFound("Staff member"));
	}

	private StaffView view(Staff s) {
		return new StaffView(s.getId(), s.getEmployeeNo(), s.details(), s.getStatus(), s.getLeavingDate(),
				s.getLeavingReason(), logins.loginOfStaff(s.getId()).orElse(null), s.getVersion());
	}

	private StaffDetails validate(StaffDetails d) {
		if (d.category() == null || !CATEGORIES.contains(d.category())) {
			throw ApiException.businessRule("CATEGORY_INVALID", "Choose teacher or other staff");
		}
		if (PakFormats.trimToNull(d.name()) == null || PakFormats.trimToNull(d.designation()) == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name and designation are required");
		}
		if (d.joiningDate() == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Joining date is required");
		}
		if (d.gender() != null && !Set.of("MALE", "FEMALE").contains(d.gender())) {
			throw ApiException.businessRule("GENDER_INVALID", "Unknown gender");
		}
		String employment = d.employmentType() == null ? "PERMANENT" : d.employmentType();
		if (!Set.of("PERMANENT", "CONTRACT").contains(employment)) {
			throw ApiException.businessRule("EMPLOYMENT_INVALID", "Choose permanent or contract");
		}
		if (d.photoFileId() != null) {
			var photo = files.find(d.photoFileId())
				.orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "Photo not found"));
			if (!"PHOTO".equals(photo.purpose())) {
				throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "Upload the picture as a photo");
			}
		}
		return new StaffDetails(d.category(), d.name().trim(), PakFormats.trimToNull(d.fatherOrHusbandName()), d.gender(),
				d.dob(), PakFormats.cnic(d.cnic(), "CNIC"), PakFormats.phone(d.phone(), "Phone"),
				PakFormats.trimToNull(d.email()), PakFormats.trimToNull(d.address()),
				PakFormats.trimToNull(d.qualification()), PakFormats.trimToNull(d.experience()), d.designation().trim(),
				employment, d.joiningDate(), d.photoFileId(), PakFormats.trimToNull(d.notes()));
	}

	private static Money parseMoney(String raw, String field) {
		try {
			BigDecimal amount = new BigDecimal(raw == null ? "" : raw.trim().replace(",", ""));
			if (amount.signum() < 0 || amount.scale() > 2) {
				throw ApiException.businessRule("AMOUNT_INVALID", field + ": enter an amount with at most 2 decimals");
			}
			return Money.of(amount);
		}
		catch (NumberFormatException ex) {
			throw ApiException.businessRule("AMOUNT_INVALID", field + ": enter an amount");
		}
	}

	private static String money(BigDecimal value) {
		return value == null ? null : Money.of(value).toString();
	}
}
