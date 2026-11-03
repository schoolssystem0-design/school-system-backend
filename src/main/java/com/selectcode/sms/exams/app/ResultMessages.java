package com.selectcode.sms.exams.app;

import com.selectcode.sms.exams.app.ResultService.ClassResults;
import com.selectcode.sms.exams.app.ResultService.StudentResult;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.MessageOutbox;
import com.selectcode.sms.shared.tenant.TenantContext;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends published results to families by SMS (FR 14.13). One message per student, to the contact who receives results,
 * otherwise the father's or mother's phone. The same Idempotency-Key never messages a student twice (the key is
 * per student, so brothers and sisters on one phone each get their own message).
 */
@Service
public class ResultMessages {

	public record ResultSmsReport(int queued, int alreadyQueued, int noPhone) {
	}

	private final JdbcTemplate jdbc;

	private final ResultService results;

	private final MessageOutbox outbox;

	private final AuditService audit;

	ResultMessages(JdbcTemplate jdbc, ResultService results, MessageOutbox outbox, AuditService audit) {
		this.jdbc = jdbc;
		this.results = results;
		this.outbox = outbox;
		this.audit = audit;
	}

	@Transactional
	public ResultSmsReport send(long examClassId, Long sectionId, List<Long> studentIds, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
			throw ApiException.businessRule("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header (a UUID)");
		}
		ClassResults r = results.results(examClassId, sectionId, null);
		if (r.live()) {
			throw ApiException.businessRule("NOT_PUBLISHED", "Publish the results before sending them to families");
		}
		String school = jdbc.queryForObject("select name from school where id = ?", String.class,
				TenantContext.requireSchoolId());
		int queued = 0;
		int already = 0;
		int noPhone = 0;
		for (StudentResult s : r.students()) {
			if (studentIds != null && !studentIds.isEmpty() && !studentIds.contains(s.studentId())) {
				continue;
			}
			var p = jdbc.queryForMap("""
					select f.father_phone, f.mother_phone, f.preferred_channel,
					       (select c.phone from student_contact c where c.student_id = st.id and c.receives_results
					          and c.phone is not null order by c.id limit 1) as contact_phone
					from student st join family f on f.id = st.family_id where st.id = ?""", s.studentId());
			String phone = p.get("contact_phone") != null ? (String) p.get("contact_phone")
					: p.get("father_phone") != null ? (String) p.get("father_phone") : (String) p.get("mother_phone");
			if (phone == null || "NONE".equals(p.get("preferred_channel"))) {
				noPhone++;
				continue;
			}
			String body = "%s: %s result of %s (%s): %s".formatted(school, r.examName(), s.name(), s.section(),
					ExamDocuments.smsSummary(s));
			if (outbox.queueSms(phone, body, "RESULT", "exam_class", examClassId,
					idempotencyKey.trim() + ":" + s.studentId())) {
				queued++;
			}
			else {
				already++;
			}
		}
		audit.record("RESULTS_SMS_SENT", "exam_class", examClassId, null,
				Map.of("queued", queued, "alreadyQueued", already, "noPhone", noPhone, "version", r.version()));
		return new ResultSmsReport(queued, already, noPhone);
	}
}
