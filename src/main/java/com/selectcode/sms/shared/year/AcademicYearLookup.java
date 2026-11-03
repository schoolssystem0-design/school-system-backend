package com.selectcode.sms.shared.year;

import java.time.LocalDate;
import java.util.Optional;

/** Read access to the current school's academic years. Implemented by the school module. */
public interface AcademicYearLookup {

	enum Status {
		UPCOMING, CURRENT, CLOSED
	}

	record YearRef(long id, String name, LocalDate startDate, LocalDate endDate, Status status) {

		public boolean closed() {
			return status == Status.CLOSED;
		}
	}

	Optional<YearRef> findById(long id);

	Optional<YearRef> current();
}
