package com.selectcode.sms.shared.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void keepsTwoDecimalsAndRoundsHalfUp() {
		assertThat(Money.of("3500")).hasToString("3500.00");
		assertThat(Money.of("10.005")).hasToString("10.01");
		assertThat(Money.of("10.004")).hasToString("10.00");
	}

	@Test
	void staffChildDiscountExample() {
		// FR 10.3: 50% staff-child discount on tuition 3,500
		assertThat(Money.of("3500").percent(new BigDecimal("50"))).isEqualTo(Money.of("1750.00"));
	}

	@Test
	void oddPercentageIsRoundedOnce() {
		// QA-FEE-028: 33.33% of 3,000 = 999.90 exactly; 33.335% of 3,000 = 1000.05
		assertThat(Money.of("3000").percent(new BigDecimal("33.33"))).isEqualTo(Money.of("999.90"));
		assertThat(Money.of("3000").percent(new BigDecimal("33.335"))).isEqualTo(Money.of("1000.05"));
	}

	@Test
	void partialPaymentLeavesBalance() {
		// FR 10.7: father pays 5,000 against 7,000
		Money balance = Money.of("7000").minus(Money.of("5000"));
		assertThat(balance).isEqualTo(Money.ofRupees(2000));
		assertThat(balance.isPositive()).isTrue();
	}

	@Test
	void equalityIgnoresInputScale() {
		assertThat(Money.of("5")).isEqualTo(Money.of("5.0")).isEqualTo(Money.of(new BigDecimal("5.000")));
		assertThat(Money.of("5").hashCode()).isEqualTo(Money.of("5.00").hashCode());
	}

	@Test
	void noFloatingPointDrift() {
		Money total = Money.ZERO;
		for (int i = 0; i < 1000; i++) {
			total = total.plus(Money.of("0.10"));
		}
		assertThat(total).isEqualTo(Money.ofRupees(100));
	}
}
