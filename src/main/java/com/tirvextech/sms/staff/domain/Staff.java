package com.selectcode.sms.staff.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/** A teacher or non-teaching employee (FR 6.1, 7.1). Leaving keeps the record and all history (FR 6.7, 7.4). */
@Entity
@Table(name = "staff")
public class Staff extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_no", nullable = false, length = 20)
	private String employeeNo;

	@Column(nullable = false, length = 10)
	private String category;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "father_or_husband_name", length = 200)
	private String fatherOrHusbandName;

	@Column(length = 10)
	private String gender;

	private LocalDate dob;

	@Column(length = 20)
	private String cnic;

	@Column(length = 30)
	private String phone;

	@Column(length = 254)
	private String email;

	@Column(length = 500)
	private String address;

	@Column(length = 200)
	private String qualification;

	@Column(length = 200)
	private String experience;

	@Column(nullable = false, length = 100)
	private String designation;

	@Column(name = "employment_type", nullable = false, length = 20)
	private String employmentType = "PERMANENT";

	@Column(name = "joining_date", nullable = false)
	private LocalDate joiningDate;

	@Column(nullable = false, length = 10)
	private String status = "ACTIVE";

	@Column(name = "leaving_date")
	private LocalDate leavingDate;

	@Column(name = "leaving_reason", length = 500)
	private String leavingReason;

	@Column(name = "photo_file_id")
	private Long photoFileId;

	@Column(length = 1000)
	private String notes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Staff() {
	}

	public Staff(String employeeNo) {
		this.employeeNo = employeeNo;
	}

	public record StaffDetails(String category, String name, String fatherOrHusbandName, String gender, LocalDate dob,
			String cnic, String phone, String email, String address, String qualification, String experience,
			String designation, String employmentType, LocalDate joiningDate, Long photoFileId, String notes) {
	}

	public void apply(StaffDetails d, Instant now) {
		category = d.category();
		name = d.name();
		fatherOrHusbandName = d.fatherOrHusbandName();
		gender = d.gender();
		dob = d.dob();
		cnic = d.cnic();
		phone = d.phone();
		email = d.email();
		address = d.address();
		qualification = d.qualification();
		experience = d.experience();
		designation = d.designation();
		employmentType = d.employmentType();
		joiningDate = d.joiningDate();
		photoFileId = d.photoFileId();
		notes = d.notes();
		updatedAt = now;
	}

	public StaffDetails details() {
		return new StaffDetails(category, name, fatherOrHusbandName, gender, dob, cnic, phone, email, address,
				qualification, experience, designation, employmentType, joiningDate, photoFileId, notes);
	}

	public void leave(LocalDate date, String reason, Instant now) {
		status = "LEFT";
		leavingDate = date;
		leavingReason = reason;
		updatedAt = now;
	}

	public void rejoin(Instant now) {
		status = "ACTIVE";
		leavingDate = null;
		leavingReason = null;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getEmployeeNo() {
		return employeeNo;
	}

	public String getCategory() {
		return category;
	}

	public String getName() {
		return name;
	}

	public String getDesignation() {
		return designation;
	}

	public String getStatus() {
		return status;
	}

	public LocalDate getJoiningDate() {
		return joiningDate;
	}

	public LocalDate getLeavingDate() {
		return leavingDate;
	}

	public String getLeavingReason() {
		return leavingReason;
	}

	public String getGender() {
		return gender;
	}

	public String getFatherOrHusbandName() {
		return fatherOrHusbandName;
	}

	public long getVersion() {
		return version;
	}
}
