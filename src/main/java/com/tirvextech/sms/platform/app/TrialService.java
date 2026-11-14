package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.app.SchoolManagementService.NewSchool;
import com.selectcode.sms.platform.app.SchoolManagementService.SchoolCreated;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.messaging.EmailSetup;
import com.selectcode.sms.shared.messaging.MessageSender;
import com.selectcode.sms.shared.messaging.OutgoingMessage;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.web.RateLimiter;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Free trial: a school signs itself up from the public site and gets full access for {@code app.trial.days} (14) days.
 * One trial per internet address (IPv6: per /64 network, which one home or office gets) and one per email address,
 * ever: the record stays when the school is deleted. The administrator chooses a password from the setup link sent to
 * their email, which also proves the address is theirs. When the trial ends the school cannot sign in; its data is kept
 * until the super admin activates full access, extends the trial or deletes the school.
 */
@Service
public class TrialService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	public record TrialRequest(String schoolName, String schoolCode, String adminName, String username, String email) {
	}

	public record TrialStarted(String schoolCode, String username, String email, Instant trialEndsAt) {
	}

	private final SchoolManagementService management;

	private final JdbcTemplate jdbc;

	private final EmailSetup email;

	private final MessageSender messages;

	private final RateLimiter rateLimiter;

	private final AuditService audit;

	private final Clock clock;

	private final int days;

	TrialService(SchoolManagementService management, JdbcTemplate jdbc, EmailSetup email, MessageSender messages,
			RateLimiter rateLimiter, AuditService audit, Clock clock, @Value("${app.trial.days:14}") int days) {
		this.management = management;
		this.jdbc = jdbc;
		this.email = email;
		this.messages = messages;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.clock = clock;
		this.days = days;
	}

	public int days() {
		return days;
	}

	public TrialStarted start(TrialRequest request, String remoteAddress) {
		String ip = network(remoteAddress);
		rateLimiter.check("trial:" + ip, 5, Duration.ofHours(1));
		String address = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
		if (!EMAIL.matcher(address).matches()) {
			throw ApiException.businessRule("EMAIL_INVALID", "Enter a valid email address: the setup link is sent there");
		}
		if (!email.ready()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRIAL_UNAVAILABLE",
					"Free trials cannot start right now. Please contact us and we will set up your school.");
		}
		String code = request.schoolCode() == null ? "" : request.schoolCode().trim().toUpperCase(Locale.ROOT);
		// Reserve the address and email first: two requests at the same moment cannot both get a trial.
		long reservation;
		try {
			reservation = jdbc.queryForObject("""
					insert into trial_signup (ip, email, school_code) values (?, ?, ?) returning id""", Long.class, ip,
					address, code);
		}
		catch (DuplicateKeyException e) {
			boolean sameAddress = Boolean.TRUE.equals(
					jdbc.queryForObject("select exists (select 1 from trial_signup where ip = ?)", Boolean.class, ip));
			throw sameAddress
					? ApiException.conflict("TRIAL_USED", "A free trial has already been used from this internet connection. "
							+ "Please contact us to continue or to buy the system.")
					: ApiException.conflict("TRIAL_EMAIL_USED", "This email address has already been used for a free trial. "
							+ "Please contact us to continue or to buy the system.");
		}
		SchoolCreated created;
		try {
			created = management.create(new NewSchool(code, request.schoolName(), request.adminName(), request.username(),
					address));
		}
		catch (RuntimeException e) {
			// Nothing was created (wrong code, code taken...): the address may try again.
			jdbc.update("delete from trial_signup where id = ?", reservation);
			throw e;
		}
		Instant ends = clock.instant().plus(Duration.ofDays(days));
		jdbc.update("update school set trial_ends_at = ? where id = ?", java.sql.Timestamp.from(ends), created.schoolId());
		jdbc.update("update trial_signup set created_school_id = ? where id = ?", created.schoolId(), reservation);
		audit.recordPlatform("TRIAL_STARTED", created.schoolId(),
				Map.of("code", created.code(), "name", created.name(), "email", address, "ip", ip, "days", days));
		String username = created.adminSetup().username();
		messages.send(OutgoingMessage.email(address, "Your free trial of the school system", """
				Welcome! %s is ready for a %d-day free trial, with every feature.

				School code: %s
				Username: %s
				Trial ends: %s

				We have sent you a separate email with a link to choose your password. Then sign in with the school code
				and username above. Your data is kept when the trial ends; contact us to continue.
				""".formatted(created.name(), days, created.code(), username,
				PdfKit.date(ends.atZone(ZoneId.of("Asia/Karachi")).toLocalDate()))));
		return new TrialStarted(created.code(), username, address, ends);
	}

	/**
	 * The trial "address": the IPv4 address, or the /64 network of an IPv6 address (a home or office is given a whole
	 * /64, so one device could otherwise switch addresses freely).
	 */
	static String network(String remoteAddress) {
		String raw = remoteAddress == null ? "" : remoteAddress.trim();
		try {
			if (raw.contains(":")) {
				byte[] bytes = InetAddress.getByName(raw.replaceAll("^\\[|\\]$", "").replaceAll("%.*$", "")).getAddress();
				if (bytes.length == 16) {
					Arrays.fill(bytes, 8, 16, (byte) 0);
					return InetAddress.getByAddress(bytes).getHostAddress().replaceAll("(:0)+$", "::") + "/64";
				}
				return InetAddress.getByAddress(bytes).getHostAddress();
			}
		}
		catch (java.net.UnknownHostException e) {
			// not an address: use the text as it is
		}
		return raw.length() > 64 ? raw.substring(0, 64) : raw;
	}
}
