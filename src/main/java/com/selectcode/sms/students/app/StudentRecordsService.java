package com.selectcode.sms.students.app;

import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.files.FileService;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.students.StudentService;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Documents (FR 4.9), extra guardians/contacts (FR 8.3) and activities (FR 5.10) of a student. */
@Service
public class StudentRecordsService {

	public static final Set<String> DOCUMENT_TYPES = Set.of("B_FORM", "BIRTH_CERTIFICATE", "SCHOOL_LEAVING_CERTIFICATE",
			"RESULT_CARD", "MEDICAL", "OTHER");

	public record StudentDocumentView(long id, String docType, String title, long fileId) {
	}

	public record ContactView(long id, String name, String relationship, String phone, String cnic,
			boolean canCollect, boolean receivesAttendance, boolean receivesFees, boolean receivesResults) {
	}

	public record ContactInput(String name, String relationship, String phone, String cnic, boolean canCollect,
			boolean receivesAttendance, boolean receivesFees, boolean receivesResults) {
	}

	public record GroupView(long id, String kind, String name, boolean active) {
	}

	public record ActivityView(long id, String kind, Long groupId, String groupName, String role, String title,
			String description, LocalDate achievedOn) {
	}

	public record ActivityInput(String kind, Long groupId, String role, String title, String description,
			LocalDate achievedOn) {
	}

	private final StudentService students;

	private final StudentAccess access;

	private final FileService files;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	StudentRecordsService(StudentService students, StudentAccess access, FileService files, AuditService audit,
			JdbcTemplate jdbc) {
		this.students = students;
		this.access = access;
		this.files = files;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------- documents

	@Transactional(readOnly = true)
	public List<StudentDocumentView> documents(long studentId) {
		students.get(studentId);
		if (!access.canSeePrivate()) {
			return List.of();
		}
		return jdbc.query("select id, doc_type, title, file_id from student_document where student_id = ? order by id",
				(rs, i) -> new StudentDocumentView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), studentId);
	}

	@Transactional
	public StudentDocumentView addDocument(long studentId, String docType, String title, long fileId) {
		students.get(studentId);
		String type = docType == null ? "OTHER" : docType;
		if (!DOCUMENT_TYPES.contains(type)) {
			throw ApiException.businessRule("DOC_TYPE_INVALID", "Unknown document type");
		}
		var file = files.find(fileId).orElseThrow(() -> ApiException.businessRule("FILE_UNKNOWN", "File not found"));
		if (!"STUDENT_DOCUMENT".equals(file.purpose())) {
			throw ApiException.businessRule("FILE_PURPOSE_MISMATCH", "Upload the file as a student document");
		}
		String cleanTitle = PakFormats.trimToNull(title) == null ? file.originalName() : title.trim();
		Long id = jdbc.queryForObject("""
				insert into student_document (school_id, student_id, doc_type, title, file_id)
				values (?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), studentId, type,
				cleanTitle, fileId);
		audit.record("STUDENT_DOCUMENT_ADDED", "student", studentId, null, Map.of("type", type, "title", cleanTitle));
		return new StudentDocumentView(id, type, cleanTitle, fileId);
	}

	@Transactional
	public void removeDocument(long studentId, long documentId) {
		students.get(studentId);
		if (jdbc.update("delete from student_document where id = ? and student_id = ?", documentId, studentId) == 0) {
			throw ApiException.notFound("Document");
		}
		audit.record("STUDENT_DOCUMENT_REMOVED", "student", studentId, Map.of("documentId", documentId), null);
	}

	// -------------------------------------------------------------- contacts

	@Transactional(readOnly = true)
	public List<ContactView> contacts(long studentId) {
		students.get(studentId);
		boolean privateOk = access.canSeePrivate();
		return jdbc.query("""
				select id, name, relationship, phone, cnic, can_collect, receives_attendance, receives_fees,
				       receives_results from student_contact where student_id = ? order by id""",
				(rs, i) -> new ContactView(rs.getLong(1), rs.getString(2), rs.getString(3),
						privateOk ? rs.getString(4) : null, privateOk ? rs.getString(5) : null, rs.getBoolean(6),
						rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9)),
				studentId);
	}

	/**
	 * A guardian or extra contact with what they may do and which messages they receive, e.g. the grandmother gets
	 * attendance messages and the father gets fee bills (FR 8.3).
	 */
	@Transactional
	public ContactView addContact(long studentId, ContactInput in) {
		students.get(studentId);
		String name = PakFormats.trimToNull(in.name());
		String relationship = PakFormats.trimToNull(in.relationship());
		if (name == null || relationship == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name and relationship are required");
		}
		String phone = PakFormats.phone(in.phone(), "Phone");
		String cnic = PakFormats.cnic(in.cnic(), "CNIC");
		Long id = jdbc.queryForObject("""
				insert into student_contact (school_id, student_id, name, relationship, phone, cnic, can_collect,
				                             receives_attendance, receives_fees, receives_results)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(),
				studentId, name, relationship, phone, cnic, in.canCollect(), in.receivesAttendance(), in.receivesFees(),
				in.receivesResults());
		audit.record("STUDENT_CONTACT_ADDED", "student", studentId, null, Map.of("name", name, "relationship", relationship));
		return new ContactView(id, name, relationship, phone, cnic, in.canCollect(), in.receivesAttendance(),
				in.receivesFees(), in.receivesResults());
	}

	@Transactional
	public void removeContact(long studentId, long contactId) {
		students.get(studentId);
		if (jdbc.update("delete from student_contact where id = ? and student_id = ?", contactId, studentId) == 0) {
			throw ApiException.notFound("Contact");
		}
		audit.record("STUDENT_CONTACT_REMOVED", "student", studentId, Map.of("contactId", contactId), null);
	}

	// ------------------------------------------------------------ activities

	@Transactional(readOnly = true)
	public List<GroupView> groups() {
		return jdbc.query("select id, kind, name, active from activity_group order by kind, name",
				(rs, i) -> new GroupView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
	}

	@Transactional
	public GroupView createGroup(String kind, String name) {
		if (!Set.of("HOUSE", "CLUB").contains(kind)) {
			throw ApiException.businessRule("KIND_INVALID", "Choose house or club");
		}
		String clean = PakFormats.trimToNull(name);
		if (clean == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Name is required");
		}
		Long id = jdbc.queryForObject("insert into activity_group (school_id, kind, name) values (?, ?, ?) returning id",
				Long.class, TenantContext.requireSchoolId(), kind, clean);
		audit.record("ACTIVITY_GROUP_CREATED", "activity_group", id, null, Map.of("kind", kind, "name", clean));
		return new GroupView(id, kind, clean, true);
	}

	@Transactional(readOnly = true)
	public List<ActivityView> activities(long studentId) {
		students.get(studentId);
		return jdbc.query("""
				select a.id, a.kind, a.group_id, g.name, a.role, a.title, a.description, a.achieved_on
				from student_activity a left join activity_group g on g.id = a.group_id
				where a.student_id = ? order by a.kind, a.achieved_on desc nulls last, a.id""",
				(rs, i) -> new ActivityView(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getString(7),
						rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate()),
				studentId);
	}

	/** House/club membership or an achievement ("shining star", FR 5.10). */
	@Transactional
	public ActivityView addActivity(long studentId, ActivityInput in) {
		students.get(studentId);
		if (!Set.of("MEMBERSHIP", "ACHIEVEMENT").contains(in.kind())) {
			throw ApiException.businessRule("KIND_INVALID", "Choose membership or achievement");
		}
		if ("MEMBERSHIP".equals(in.kind()) && in.groupId() == null) {
			throw ApiException.businessRule("GROUP_REQUIRED", "Choose the house or club");
		}
		if ("ACHIEVEMENT".equals(in.kind()) && PakFormats.trimToNull(in.title()) == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Describe the achievement");
		}
		Long id = jdbc.queryForObject("""
				insert into student_activity (school_id, student_id, kind, group_id, role, title, description, achieved_on)
				values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireSchoolId(), studentId,
				in.kind(), in.groupId(), PakFormats.trimToNull(in.role()), PakFormats.trimToNull(in.title()),
				PakFormats.trimToNull(in.description()), in.achievedOn() == null ? null : Date.valueOf(in.achievedOn()));
		audit.record("STUDENT_ACTIVITY_ADDED", "student", studentId, null, Map.of("kind", in.kind()));
		return activities(studentId).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
	}

	@Transactional
	public void removeActivity(long studentId, long activityId) {
		students.get(studentId);
		if (jdbc.update("delete from student_activity where id = ? and student_id = ?", activityId, studentId) == 0) {
			throw ApiException.notFound("Activity");
		}
		audit.record("STUDENT_ACTIVITY_REMOVED", "student", studentId, Map.of("activityId", activityId), null);
	}
}
