package com.selectcode.sms.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in Pakistani rupees, always held at 2 decimal places with HALF_UP rounding. Never use {@code double} or
 * {@code float} for money (enforced by an architecture test).
 */
public final class Money implements Comparable<Money> {

	public static final int SCALE = 2;

	public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	public static final Money ZERO = new Money(BigDecimal.ZERO);

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final BigDecimal amount;

	private Money(BigDecimal amount) {
		this.amount = amount.setScale(SCALE, ROUNDING);
	}

	public static Money of(BigDecimal amount) {
		return new Money(Objects.requireNonNull(amount, "amount"));
	}

	/** Parses a plain decimal string such as {@code "3500"} or {@code "3500.50"} (the API's money format). */
	public static Money of(String amount) {
		return new Money(new BigDecimal(Objects.requireNonNull(amount, "amount").trim()));
	}

	public static Money ofRupees(long rupees) {
		return new Money(BigDecimal.valueOf(rupees));
	}

	public BigDecimal amount() {
		return amount;
	}

	public Money plus(Money other) {
		return new Money(amount.add(other.amount));
	}

	public Money minus(Money other) {
		return new Money(amount.subtract(other.amount));
	}

	public Money times(BigDecimal factor) {
		return new Money(amount.multiply(factor));
	}

	/** The given percentage of this amount, e.g. {@code percent(50)} of 3,500.00 is 1,750.00. Rounded once. */
	public Money percent(BigDecimal percentage) {
		return new Money(amount.multiply(percentage).divide(HUNDRED, SCALE, ROUNDING));
	}

	public Money negate() {
		return new Money(amount.negate());
	}

	public Money min(Money other) {
		return compareTo(other) <= 0 ? this : other;
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

	public boolean isNegative() {
		return amount.signum() < 0;
	}

	public boolean isPositive() {
		return amount.signum() > 0;
	}

	@Override
	public int compareTo(Money other) {
		return amount.compareTo(other.amount);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Money money && amount.equals(money.amount);
	}

	@Override
	public int hashCode() {
		return amount.hashCode();
	}

	/** Plain string with 2 decimals, e.g. {@code "3500.00"}: the JSON wire format. */
	@Override
	public String toString() {
		return amount.toPlainString();
	}
}
