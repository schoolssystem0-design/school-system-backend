package com.selectcode.sms.shared.year;

import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.Permissions;
import com.selectcode.sms.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The academic year a request works in (FR 1.2, 15.1): the {@code X-Academic-Year} header if sent (must belong to the
 * current school), otherwise the school's current year. Resolved lazily and cached per request.
 */
@Component
public class YearContext {

	public static final String HEADER = "X-Academic-Year";

	private static final String CACHE_ATTRIBUTE = YearContext.class.getName();

	private final AcademicYearLookup years;

	public YearContext(AcademicYearLookup years) {
		this.years = years;
	}

	/** The selected year, or empty if the school has no academic year yet. */
	public Optional<AcademicYearLookup.YearRef> selected() {
		HttpServletRequest request = currentRequest();
		if (request != null && request.getAttribute(CACHE_ATTRIBUTE) instanceof Optional<?> cached) {
			@SuppressWarnings("unchecked")
			Optional<AcademicYearLookup.YearRef> result = (Optional<AcademicYearLookup.YearRef>) cached;
			return result;
		}
		Optional<AcademicYearLookup.YearRef> result = resolve(request);
		if (request != null) {
			request.setAttribute(CACHE_ATTRIBUTE, result);
		}
		return result;
	}

	public AcademicYearLookup.YearRef requireSelected() {
		return selected().orElseThrow(() -> ApiException.businessRule("NO_ACADEMIC_YEAR",
				"Create and open an academic year first"));
	}

	/** The selected year, if changes are allowed in it: closed years need {@code school.year.correct_closed}. */
	public AcademicYearLookup.YearRef requireWritable() {
		AcademicYearLookup.YearRef year = requireSelected();
		requireWritable(year);
		return year;
	}

	public void requireWritable(AcademicYearLookup.YearRef year) {
		if (year.closed() && !CurrentActor.hasPermission(Permissions.SCHOOL_YEAR_CORRECT_CLOSED)) {
			throw ApiException.conflict("YEAR_CLOSED",
					"Academic year " + year.name() + " is closed. Only authorised corrections are allowed.");
		}
	}

	private Optional<AcademicYearLookup.YearRef> resolve(HttpServletRequest request) {
		String header = request == null ? null : request.getHeader(HEADER);
		if (header == null || header.isBlank()) {
			return years.current();
		}
		long id;
		try {
			id = Long.parseLong(header.trim());
		}
		catch (NumberFormatException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACADEMIC_YEAR", "Invalid academic year");
		}
		return Optional.of(years.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACADEMIC_YEAR",
					"Unknown academic year")));
	}

	private static HttpServletRequest currentRequest() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
				? attributes.getRequest() : null;
	}
}
