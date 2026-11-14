package com.selectcode.sms.admissions.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** An admission enquiry, from first contact to enrolment (FR 4.1–4.6). */
@Entity
@Table(name = "enquiry")
public class Enquiry extends TenantScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "registration_no", nullable = false, length = 20)
	private String registrationNo;

	@Column(name = "enquiry_date", nullable = false)
	private LocalDate enquiryDate;

	@Column(nullable = false, length = 10)
	private String source;

	@Column(name = "child_name", nullable = false, length = 200)
	private String childName;

	@Column(length = 10)
	private String gender;

	private LocalDate dob;

	@Column(name = "class_applied_id")
	private Long classAppliedId;

	@Column(name = "academic_year_id")
	private Long academicYearId;

	@Column(name = "parent_name", length = 200)
	private String parentName;

	@Column(name = "parent_phone", length = 30)
	private String parentPhone;

	@Column(name = "parent_cnic", length = 20)
	private String parentCnic;

	@Column(length = 500)
	private String address;

	@Column(name = "previous_school", length = 200)
	private String previousSchool;

	@Column(length = 1000)
	private String remarks;

	@Column(name = "status_id", nullable = false)
	private Long statusId;

	@Column(name = "test_date")
	private LocalDate testDate;

	@Column(name = "test_obtained", precision = 6, scale = 2)
	private BigDecimal testObtained;

	@Column(name = "test_total", precision = 6, scale = 2)
	private BigDecimal testTotal;

	@Column(name = "test_remark", length = 500)
	private String testRemark;

	@Column(name = "student_id")
	private Long studentId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Enquiry() {
	}

	public Enquiry(String registrationNo, long statusId) {
		this.registrationNo = registrationNo;
		this.statusId = statusId;
	}

	public record EnquiryDetails(LocalDate enquiryDate, String source, String childName, String gender, LocalDate dob,
			Long classAppliedId, Long academicYearId, String parentName, String parentPhone, String parentCnic,
			String address, String previousSchool, String remarks) {
	}

	public record TestResult(LocalDate testDate, BigDecimal obtained, BigDecimal total, String remark) {
	}

	public void apply(EnquiryDetails d, Instant now) {
		enquiryDate = d.enquiryDate();
		source = d.source();
		childName = d.childName();
		gender = d.gender();
		dob = d.dob();
		classAppliedId = d.classAppliedId();
		academicYearId = d.academicYearId();
		parentName = d.parentName();
		parentPhone = d.parentPhone();
		parentCnic = d.parentCnic();
		address = d.address();
		previousSchool = d.previousSchool();
		remarks = d.remarks();
		updatedAt = now;
	}

	public EnquiryDetails details() {
		return new EnquiryDetails(enquiryDate, source, childName, gender, dob, classAppliedId, academicYearId, parentName,
				parentPhone, parentCnic, address, previousSchool, remarks);
	}

	public void setTest(TestResult t, Instant now) {
		testDate = t.testDate();
		testObtained = t.obtained();
		testTotal = t.total();
		testRemark = t.remark();
		updatedAt = now;
	}

	public TestResult test() {
		return new TestResult(testDate, testObtained, testTotal, testRemark);
	}

	public void changeStatus(long statusId, Instant now) {
		this.statusId = statusId;
		updatedAt = now;
	}

	public void enrolled(long studentId, long enrolledStatusId, Instant now) {
		this.studentId = studentId;
		this.statusId = enrolledStatusId;
		updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getRegistrationNo() {
		return registrationNo;
	}

	public Long getStatusId() {
		return statusId;
	}

	public Long getStudentId() {
		return studentId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public long getVersion() {
		return version;
	}
}
