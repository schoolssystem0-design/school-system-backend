package com.selectcode.sms.exams.app;

import com.selectcode.sms.communication.MessageTemplates;
import com.selectcode.sms.exams.app.TestMarksService.TestMarkRow;
import com.selectcode.sms.exams.app.TestMarksService.TestSheet;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends one test's marks to families by SMS: one message per student, in the family's language, using the school's
 * "Test marks" template. Each student's marks for a test are sent once, however often the button is pressed.
 */
@Service
public class TestMarkMessages {

	public record TestMarksSmsReport(int queued, int alreadySent, int noPhone, int noMarks) {
	}

	private final JdbcTemplate jdbc;

	private final TestMarksService tests;

	private final MessageTemplates templates;

	private final MessageOutbox outbox;

	private final AuditService audit;

	TestMarkMessages(JdbcTemplate jdbc, TestMarksService tests, MessageTemplates templates, MessageOutbox outbox,
			AuditService audit) {
		this.jdbc = jdbc;
		this.tests = tests;
		this.templates = templates;
		this.outbox = outbox;
		this.audit = audit;
	}

	@Transactional
	public TestMarksSmsReport send(long testId) {
		TestSheet sheet = tests.sheet(testId); // checks the user may see this section
		if (sheet.rows().stream().noneMatch(r -> r.absent() || r.obtained() != null)) {
			throw ApiException.businessRule("NO_MARKS", "Enter the marks before sending them to families");
		}
		String date = com.selectcode.sms.shared.pdf.PdfKit.date(sheet.test().testDate());
		int queued = 0;
		int already = 0;
		int noPhone = 0;
		int noMarks = 0;
		for (TestMarkRow row : sheet.rows()) {
			if (!row.absent() && row.obtained() == null) {
				noMarks++;
				continue;
			}
			List<Map<String, Object>> family = jdbc.queryForList("""
					select coalesce((select c.phone from student_contact c where c.student_id = s.id and c.receives_results
					                   and c.phone is not null order by c.id limit 1), f.father_phone, f.mother_phone) as phone,
					       f.preferred_channel, f.message_language
					from student s join family f on f.id = s.family_id where s.id = ?""", row.studentId());
			if (family.isEmpty() || family.getFirst().get("phone") == null
					|| "NONE".equals(family.getFirst().get("preferred_channel"))) {
				noPhone++;
				continue;
			}
			String language = MessageTemplates.language((String) family.getFirst().get("message_language"));
			boolean urdu = MessageTemplates.ROMAN_URDU.equals(language);
			String marks = row.absent() ? (urdu ? "ghair hazir" : "absent")
					: row.obtained() + "/" + sheet.test().maxMarks() + (urdu ? " number" : "");
			String body = templates.render(MessageTemplates.Code.TEST_MARKS, Map.of("name", row.name(), "subject",
					sheet.test().subjectName(), "date", date, "marks", marks), language);
			if (outbox.queueSms((String) family.getFirst().get("phone"), body, "TEST_MARKS", "class_test", testId,
					"testmarks-" + testId + "-" + row.studentId())) {
				queued++;
			}
			else {
				already++;
			}
		}
		audit.record("TEST_MARKS_SMS_SENT", "class_test", testId, null,
				Map.of("queued", queued, "alreadySent", already, "noPhone", noPhone));
		return new TestMarksSmsReport(queued, already, noPhone, noMarks);
	}
}
