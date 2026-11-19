package com.tirvextech.sms.school.app;

import com.tirvextech.sms.communication.FamilyOutbox;
import com.tirvextech.sms.communication.MessageTemplates;
import com.tirvextech.sms.school.domain.CalendarDayOff;
import com.tirvextech.sms.settings.SettingKey;
import com.tirvextech.sms.settings.SettingsService;
import com.tirvextech.sms.shared.pdf.PdfKit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Optional Urdu voice / SMS when a holiday is added: "School closed on Monday". */
@Component
class HolidayParentNotifier {

	private final JdbcTemplate jdbc;

	private final FamilyOutbox familyOutbox;

	private final SettingsService settings;

	HolidayParentNotifier(JdbcTemplate jdbc, FamilyOutbox familyOutbox, SettingsService settings) {
		this.jdbc = jdbc;
		this.familyOutbox = familyOutbox;
		this.settings = settings;
	}

	@Transactional
	void notify(CalendarDayOff day) {
		if (!settings.bool(SettingKey.NOTIFY_SCHOOL_CLOSURE)) {
			return;
		}
		String dateLabel = PdfKit.date(day.getDateFrom());
		if (!day.getDateFrom().equals(day.getDateTo())) {
			dateLabel += " to " + PdfKit.date(day.getDateTo());
		}
		String detail = day.getTitle();
		List<Map<String, Object>> families = jdbc.queryForList("""
				select id, coalesce(father_phone, mother_phone) as phone, preferred_channel, message_language from family
				where coalesce(father_phone, mother_phone) is not null""");
		for (Map<String, Object> f : families) {
			String phone = (String) f.get("phone");
			if (phone == null) {
				continue;
			}
			long familyId = ((Number) f.get("id")).longValue();
			familyOutbox.queueTemplated(phone, (String) f.get("preferred_channel"), (String) f.get("message_language"),
					MessageTemplates.Code.SCHOOL_CLOSED, Map.of("date", dateLabel, "detail", detail), "SCHOOL_CLOSED", "family",
					familyId, "holiday-" + day.getId() + "-" + familyId);
		}
	}
}