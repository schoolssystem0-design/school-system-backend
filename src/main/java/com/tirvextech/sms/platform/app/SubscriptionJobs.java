package com.tirvextech.sms.platform.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning: issue the invoices due within a week, then send due-date and access-paused emails. */
@Component
class SubscriptionJobs {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionJobs.class);

	private final SubscriptionService billing;

	private final boolean enabled;

	SubscriptionJobs(SubscriptionService billing, @Value("${app.outbox.auto-dispatch:true}") boolean enabled) {
		this.billing = billing;
		this.enabled = enabled;
	}

	@Scheduled(cron = "0 30 6 * * *", zone = "Asia/Karachi")
	void daily() {
		if (!enabled) {
			return;
		}
		try {
			var today = billing.today();
			int issued = billing.issueDue(today, null);
			int notices = billing.sendNotices(today);
			if (issued + notices > 0) {
				log.info("Billing: {} invoices issued, {} reminders sent", issued, notices);
			}
		}
		catch (RuntimeException e) {
			log.error("Billing run failed", e);
		}
	}
}
