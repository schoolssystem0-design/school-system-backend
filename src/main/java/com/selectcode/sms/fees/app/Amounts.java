package com.selectcode.sms.fees.app;

import com.selectcode.sms.shared.money.Money;
import com.selectcode.sms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/** Money parsing and formatting for the fees API: amounts travel as strings ("3500.00"), never as numbers. */
public final class Amounts {

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private Amounts() {
	}

	/** Parses a positive (or zero, if allowed) amount entered by a user, e.g. "3,500" or "3500.50". */
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

	public static String text(BigDecimal value) {
		return value == null ? null : Money.of(value).toString();
	}

	public static BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	/** Rs. 3,500 / Rs. 3,500.50 for printed documents and SMS. */
	public static String rupees(BigDecimal value) {
		BigDecimal v = Money.of(nz(value)).amount();
		java.text.DecimalFormat format = new java.text.DecimalFormat(v.stripTrailingZeros().scale() > 0 ? "#,##0.00"
				: "#,##0");
		return "Rs. " + format.format(v);
	}

	public static LocalDate today(java.time.Clock clock) {
		return LocalDate.now(clock.withZone(ZONE));
	}
}
