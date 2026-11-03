package com.selectcode.sms.students.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import com.selectcode.sms.students.StudentDetails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/** A student (FR 4.7, 5.1): the centre of the system. Class membership over time is in {@code enrolment}. */
@Entity
@Table(name = "student")
public class Student extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "gr_no", nullable = false, length = 20)
	private String grNo;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 10)
	private String gender;

	private LocalDate dob;

	@Column(name = "place_of_birth", length = 100)
	private String placeOfBirth;

	@Column(name = "b_form_no", length = 20)
	private String bFormNo;

	@Column(length = 40)
	private String religion;

	@Column(length = 40)
	private String nationality;

	@Column(length = 30)
	private String phone;

	@Column(length = 500)
	private String address;

	@Column(name = "admission_date", nullable = false)
	private LocalDate admissionDate;

	@Column(name = "first_class_id")
	private Long firstClassId;

	@Column(name = "previous_school", length = 200)
	private String previousSchool;

	@Column(name = "study_group", length = 40)
	private String studyGroup;

	@Column(name = "seat_no", length = 30)
	private String seatNo;

	@Column(name = "photo_file_id")
	private Long photoFileId;

	@Column(name = "family_id", nullable = false)
	private Long familyId;

	@Column(name = "enquiry_id")
	private Long enquiryId;

	@Column(nullable = false, length = 10)
	private String status = "ACTIVE";

	@Column(name = "left_date")
	private LocalDate leftDate;

	@Column(length = 1000)
	private String notes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Student() {
	}

	public Student(String grNo, long familyId, LocalDate admissionDate, Long firstClassId, Long enquiryId) {
		this.grNo = grNo;
		this.familyId = familyId;
		this.admissionDate = admissionDate;
		this.firstClassId = firstClassId;
		this.enquiryId = enquiryId;
	}


	public void apply(StudentDetails d, Instant now) {
		name = d.name();
		gender = d.gender();
		dob = d.dob();
		placeOfBirth = d.placeOfBirth();
		bFormNo = d.bFormNo();
		religion = d.religion();
		nationality = d.nationality();
		phone = d.phone();
		address = d.address();
		previousSchool = d.previousSchool();
		studyGroup = d.studyGroup();
		seatNo = d.seatNo();
		photoFileId = d.photoFileId();
		notes = d.notes();
		updatedAt = now;
	}

	public StudentDetails details() {
		return new StudentDetails(name, gender, dob, placeOfBirth, bFormNo, religion, nationality, phone, address,
				previousSchool, studyGroup, seatNo, photoFileId, notes);
	}

	public void withdraw(LocalDate date, Instant now) {
		status = "LEFT";
		leftDate = date;
		updatedAt = now;
	}

	/** Re-admission of a former student keeps the same GR number and history. */
	public void readmit(Instant now) {
		status = "ACTIVE";
		leftDate = null;
		updatedAt = now;
	}

	public void changeGrNo(String grNo, Instant now) {
		this.grNo = grNo;
		updatedAt = now;
	}

	public void changeFamily(long familyId, Instant now) {
		this.familyId = familyId;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getGrNo() {
		return grNo;
	}

	public String getName() {
		return name;
	}

	public String getGender() {
		return gender;
	}

	public LocalDate getDob() {
		return dob;
	}

	public LocalDate getAdmissionDate() {
		return admissionDate;
	}

	public Long getFirstClassId() {
		return firstClassId;
	}

	public Long getFamilyId() {
		return familyId;
	}

	public Long getEnquiryId() {
		return enquiryId;
	}

	public String getStatus() {
		return status;
	}

	public LocalDate getLeftDate() {
		return leftDate;
	}

	public Long getPhotoFileId() {
		return photoFileId;
	}

	public long getVersion() {
		return version;
	}
}
