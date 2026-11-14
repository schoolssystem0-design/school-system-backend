package com.selectcode.sms.shared.money;

import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Parsing user-entered amounts and formatting amounts for the API ("3500.00") and for print/SMS ("Rs. 3,500"). */
public final class MoneyInput {

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private MoneyInput() {
	}

	public static BigDecimal parse(String raw, String field, boolean allowZero) {
		BigDecimal value;
		try {
			value = new BigDecimal(raw == null ? "" : raw.trim().replace(",", ""));
		}
		catch (NumberFormatException e) {
			throw ApiException.businessRule("AMOUNT_INVALID", field + ": enter an amount like 3500 or 3500.50");
		}
		if (value.scale() > 2) {
			throw ApiException.businessRule("AMOUNT_INVALID", field + ": at most 2 decimal places");
		}
		if (value.signum() < 0 || (!allowZero && value.signum() == 0)) {
			throw ApiException.businessRule("AMOUNT_INVALID", field + " must be more than zero");
		}
		if (value.compareTo(new BigDecimal("999999999999.99")) > 0) {
			throw ApiException.businessRule("AMOUNT_INVALID", field + " is too large");
		}
		return Money.of(value).amount();
	}

	/** Optional amount: blank means zero. */
	public static BigDecimal parseOrZero(String raw, String field) {
		return raw == null || raw.isBlank() ? BigDecimal.ZERO.setScale(2) : parse(raw, field, true);
	}

	public static String text(BigDecimal value) {
		return value == null ? null : Money.of(value).toString();
	}

	public static BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	public static String rupees(BigDecimal value) {
		BigDecimal v = Money.of(nz(value)).amount();
		java.text.DecimalFormat format = new java.text.DecimalFormat(
				v.stripTrailingZeros().scale() > 0 ? "#,##0.00" : "#,##0");
		return "Rs. " + format.format(v);
	}

	public static LocalDate today(Clock clock) {
		return LocalDate.now(clock.withZone(ZONE));
	}
}
