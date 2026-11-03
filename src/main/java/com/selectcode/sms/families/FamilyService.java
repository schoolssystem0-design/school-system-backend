package com.selectcode.sms.families;

import com.selectcode.sms.families.domain.Family;
import com.selectcode.sms.families.infra.FamilyRepository;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.persistence.NumberSequences;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.PageResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Families (FR 8): one record per family, shared by all siblings. Also the module's public API for students and
 * admissions. Contact details (phones, CNIC, address) are only returned to users with
 * {@code students.private.view} (FR 5.11).
 */
@Service
public class FamilyService {

	public static final Set<String> BULK_FIELDS = Set.of("FATHER_PHONE", "MOTHER_PHONE", "FATHER_CNIC", "MOTHER_CNIC",
			"FATHER_NAME", "ADDRESS");

	/** {@code contactsHidden} = phones/CNIC/address removed because the viewer lacks the private-view permission. */
	public record FamilyView(long id, String familyNo, FamilyDetails details, long children, boolean contactsHidden,
			long version) {
	}

	public record FamilyRow(long id, String familyNo, String fatherName, String fatherPhone, String motherName,
			long children) {
	}

	public record DataIssue(long familyId, String familyNo, String fatherName, String issue) {
	}

	public record FamilyBulkValue(long familyId, String value) {
	}

	private final FamilyRepository families;

	private final NumberSequences sequences;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	FamilyService(FamilyRepository families, NumberSequences sequences, AuditService audit, JdbcTemplate jdbc,
			Clock clock) {
		this.families = families;
		this.sequences = sequences;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Search by family no., parent name, phone or CNIC (FR 8.2). */
	@Transactional(readOnly = true)
	public PageResponse<FamilyRow> search(String q, int page, int size) {
		String text = q == null ? "" : q.trim().toLowerCase();
		String phone = PakFormats.phoneOrNull(q);
		String cnic = PakFormats.cnicOrNull(q);
		// Leading space kept explicitly: text blocks strip it.
		String where = " " + """
				 where (? = '' or lower(f.family_no) = ? or lower(coalesce(f.father_name, '')) like ?
				        or lower(coalesce(f.mother_name, '')) like ? or f.father_phone = ? or f.mother_phone = ?
				        or f.father_cnic = ? or f.mother_cnic = ?)""";
		Object[] args = { text, text, "%" + text + "%", "%" + text + "%", phone, phone, cnic, cnic };
		Long total = jdbc.queryForObject("select count(*) from family f" + where, Long.class, args);
		List<Object> pageArgs = new ArrayList<>(java.util.Arrays.asList(args)); // args may contain nulls
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		boolean contacts = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);
		List<FamilyRow> rows = jdbc.query("""
				select f.id, f.family_no, f.father_name, f.father_phone, f.mother_name,
				       (select count(*) from student s where s.family_id = f.id and s.status = 'ACTIVE')
				from family f""" + where + " order by f.family_no desc limit ? offset ?",
				(rs, i) -> new FamilyRow(rs.getLong(1), rs.getString(2), rs.getString(3),
						contacts ? rs.getString(4) : null, rs.getString(5), rs.getLong(6)),
				pageArgs.toArray());
		return PageResponse.of(rows, page, size, total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public FamilyView get(long id) {
		return view(find(id));
	}

	@Transactional
	public FamilyView create(FamilyDetails input) {
		FamilyDetails clean = validate(input);
		Family family = new Family(NumberSequences.format("F", sequences.next("family"), 4));
		family.apply(clean, clock.instant());
		family = families.save(family);
		audit.record("FAMILY_CREATED", "family", family.getId(), null, Map.of("familyNo", family.getFamilyNo()));
		return view(family);
	}

	/** Editing the family updates every linked child at once (FR 8.4); old/new values go to the activity history. */
	@Transactional
	public FamilyView update(long id, FamilyDetails input, long expectedVersion) {
		Family family = find(id);
		if (family.getVersion() != expectedVersion) {
			throw ApiException.conflict("CONFLICT_VERSION", "The family was changed by someone else. Reload and try again.");
		}
		FamilyDetails before = family.details();
		FamilyDetails incoming = input;
		if (!CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW)) {
			// The editor never saw the contact details (they were hidden), so keep the stored ones.
			incoming = new FamilyDetails(input.fatherName(), before.fatherCnic(), before.fatherPhone(),
					input.fatherProfession(), input.motherName(), before.motherCnic(), before.motherPhone(),
					before.email(), before.address(), input.emergencyName(), before.emergencyPhone(),
					input.preferredChannel(), input.remarks());
		}
		family.apply(validate(incoming), clock.instant());
		families.flush();
		audit.record("FAMILY_UPDATED", "family", id, before, family.details());
		return view(family);
	}

	/** Bulk fix of one field across many families (FR 8.5). */
	@Transactional
	public int bulkUpdate(String field, List<FamilyBulkValue> values) {
		if (!BULK_FIELDS.contains(field)) {
			throw ApiException.businessRule("FIELD_NOT_ALLOWED", "This field cannot be changed in bulk");
		}
		int count = 0;
		for (FamilyBulkValue v : values) {
			Family family = find(v.familyId());
			String value = switch (field) {
				case "FATHER_PHONE", "MOTHER_PHONE" -> PakFormats.phone(v.value(), family.getFamilyNo());
				case "FATHER_CNIC", "MOTHER_CNIC" -> PakFormats.cnic(v.value(), family.getFamilyNo());
				default -> PakFormats.trimToNull(v.value());
			};
			FamilyDetails before = family.details();
			family.setField(field, value, clock.instant());
			audit.record("FAMILY_BULK_UPDATED", "family", family.getId(), before, family.details(), field);
			count++;
		}
		return count;
	}

	/** Incomplete or suspicious family records (FR 8.5). */
	@Transactional(readOnly = true)
	public List<DataIssue> dataIssues() {
		return jdbc.query("""
				select f.id, f.family_no, f.father_name, issue from (
				  select id, 'No phone number' as issue from family where father_phone is null and mother_phone is null
				  union all
				  select id, 'Father CNIC missing' from family where father_cnic is null
				  union all
				  select f.id, 'No children linked' from family f
				    where not exists (select 1 from student s where s.family_id = f.id)
				) x join family f on f.id = x.id
				order by f.family_no, issue limit 500""",
				(rs, i) -> new DataIssue(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
	}

	/** Families matching a phone or CNIC (duplicate check during admission, FR 4.5). */
	@Transactional(readOnly = true)
	public List<FamilyRow> findByContact(String phone, String cnic) {
		String p = PakFormats.phoneOrNull(phone);
		String c = PakFormats.cnicOrNull(cnic);
		if (p == null && c == null) {
			return List.of();
		}
		return jdbc.query("""
				select f.id, f.family_no, f.father_name, f.father_phone, f.mother_name,
				       (select count(*) from student s where s.family_id = f.id and s.status = 'ACTIVE')
				from family f
				where f.father_phone = ? or f.mother_phone = ? or f.father_cnic = ? or f.mother_cnic = ?
				limit 10""", (rs, i) -> new FamilyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
				rs.getString(5), rs.getLong(6)), p, p, c, c);
	}

	@Transactional(readOnly = true)
	public boolean exists(long id) {
		return families.existsById(id);
	}

	/** Father's name for certificates ("son/daughter of …"). */
	@Transactional(readOnly = true)
	public String fatherName(long id) {
		return find(id).getFatherName();
	}

	private Family find(long id) {
		return families.findById(id).orElseThrow(() -> ApiException.notFound("Family"));
	}

	private FamilyView view(Family family) {
		Long children = jdbc.queryForObject("select count(*) from student where family_id = ? and status = 'ACTIVE'",
				Long.class, family.getId());
		FamilyDetails d = family.details();
		boolean contacts = CurrentActor.hasPermission(Permissions.STUDENTS_PRIVATE_VIEW);
		FamilyDetails shown = contacts ? d
				: new FamilyDetails(d.fatherName(), null, null, d.fatherProfession(), d.motherName(), null, null, null, null,
						d.emergencyName(), null, d.preferredChannel(), d.remarks());
		return new FamilyView(family.getId(), family.getFamilyNo(), shown, children == null ? 0 : children, !contacts,
				family.getVersion());
	}

	static FamilyDetails validate(FamilyDetails d) {
		if (PakFormats.trimToNull(d.fatherName()) == null && PakFormats.trimToNull(d.motherName()) == null) {
			throw ApiException.businessRule("PARENT_REQUIRED", "Enter the father's or mother's name");
		}
		String channel = d.preferredChannel() == null ? "SMS" : d.preferredChannel();
		if (!Set.of("SMS", "WHATSAPP", "NONE").contains(channel)) {
			throw ApiException.businessRule("CHANNEL_INVALID", "Unknown contact channel");
		}
		return new FamilyDetails(PakFormats.trimToNull(d.fatherName()), PakFormats.cnic(d.fatherCnic(), "Father CNIC"),
				PakFormats.phone(d.fatherPhone(), "Father phone"), PakFormats.trimToNull(d.fatherProfession()),
				PakFormats.trimToNull(d.motherName()), PakFormats.cnic(d.motherCnic(), "Mother CNIC"),
				PakFormats.phone(d.motherPhone(), "Mother phone"), PakFormats.trimToNull(d.email()),
				PakFormats.trimToNull(d.address()), PakFormats.trimToNull(d.emergencyName()),
				PakFormats.phone(d.emergencyPhone(), "Emergency phone"), channel, PakFormats.trimToNull(d.remarks()));
	}
}
