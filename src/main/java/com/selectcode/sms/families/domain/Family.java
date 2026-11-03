package com.selectcode.sms.families.domain;

import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** One family record linking parents/guardian to all their children at the school (FR 8.1). */
@Entity
@Table(name = "family")
public class Family extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "family_no", nullable = false, length = 20)
	private String familyNo;

	@Column(name = "father_name", length = 200)
	private String fatherName;

	@Column(name = "father_cnic", length = 20)
	private String fatherCnic;

	@Column(name = "father_phone", length = 30)
	private String fatherPhone;

	@Column(name = "father_profession", length = 100)
	private String fatherProfession;

	@Column(name = "mother_name", length = 200)
	private String motherName;

	@Column(name = "mother_cnic", length = 20)
	private String motherCnic;

	@Column(name = "mother_phone", length = 30)
	private String motherPhone;

	@Column(length = 254)
	private String email;

	@Column(length = 500)
	private String address;

	@Column(name = "emergency_name", length = 200)
	private String emergencyName;

	@Column(name = "emergency_phone", length = 30)
	private String emergencyPhone;

	@Column(name = "preferred_channel", nullable = false, length = 20)
	private String preferredChannel = "SMS";

	@Column(length = 1000)
	private String remarks;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Family() {
	}

	public Family(String familyNo) {
		this.familyNo = familyNo;
	}


	public void apply(FamilyDetails d, Instant now) {
		fatherName = d.fatherName();
		fatherCnic = d.fatherCnic();
		fatherPhone = d.fatherPhone();
		fatherProfession = d.fatherProfession();
		motherName = d.motherName();
		motherCnic = d.motherCnic();
		motherPhone = d.motherPhone();
		email = d.email();
		address = d.address();
		emergencyName = d.emergencyName();
		emergencyPhone = d.emergencyPhone();
		preferredChannel = d.preferredChannel() == null ? "SMS" : d.preferredChannel();
		remarks = d.remarks();
		updatedAt = now;
	}

	public FamilyDetails details() {
		return new FamilyDetails(fatherName, fatherCnic, fatherPhone, fatherProfession, motherName, motherCnic, motherPhone,
				email, address, emergencyName, emergencyPhone, preferredChannel, remarks);
	}

	/** Bulk data fix (FR 8.5): set one field. */
	public void setField(String field, String value, Instant now) {
		switch (field) {
			case "FATHER_PHONE" -> fatherPhone = value;
			case "MOTHER_PHONE" -> motherPhone = value;
			case "FATHER_CNIC" -> fatherCnic = value;
			case "MOTHER_CNIC" -> motherCnic = value;
			case "FATHER_NAME" -> fatherName = value;
			case "ADDRESS" -> address = value;
			default -> throw new IllegalArgumentException(field);
		}
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getFamilyNo() {
		return familyNo;
	}

	public String getFatherName() {
		return fatherName;
	}

	public String getFatherPhone() {
		return fatherPhone;
	}

	public String getMotherName() {
		return motherName;
	}

	public String getMotherPhone() {
		return motherPhone;
	}

	public long getVersion() {
		return version;
	}
}
