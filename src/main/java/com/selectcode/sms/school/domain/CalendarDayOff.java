package com.selectcode.sms.school.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A public holiday or vacation period (FR 1.4): no attendance is expected on these days. */
@Entity
@Table(name = "calendar_day_off")
public class CalendarDayOff extends TenantScopedEntity {

	public enum DayOffType {
		HOLIDAY, VACATION
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "academic_year_id", nullable = false)
	private Long academicYearId;

	@Column(name = "date_from", nullable = false)
	private LocalDate dateFrom;

	@Column(name = "date_to", nullable = false)
	private LocalDate dateTo;

	@Column(nullable = false, length = 200)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DayOffType type;

	protected CalendarDayOff() {
	}

	public CalendarDayOff(long academicYearId, LocalDate dateFrom, LocalDate dateTo, String title, DayOffType type) {
		this.academicYearId = academicYearId;
		this.dateFrom = dateFrom;
		this.dateTo = dateTo;
		this.title = title;
		this.type = type;
	}

	public boolean covers(LocalDate date) {
		return !date.isBefore(dateFrom) && !date.isAfter(dateTo);
	}

	public Long getId() {
		return id;
	}

	public Long getAcademicYearId() {
		return academicYearId;
	}

	public LocalDate getDateFrom() {
		return dateFrom;
	}

	public LocalDate getDateTo() {
		return dateTo;
	}

	public String getTitle() {
		return title;
	}

	public DayOffType getType() {
		return type;
	}
}
