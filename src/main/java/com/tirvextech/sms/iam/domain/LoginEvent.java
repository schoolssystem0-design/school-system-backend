package com.selectcode.sms.iam.domain;

import com.selectcode.sms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Sign-in history (FR 2.7). Insert-only. */
@Entity
@Table(name = "login_event")
public class LoginEvent extends TenantScopedEntity {

	public enum Result {
		SUCCESS, INVALID, BLOCKED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(nullable = false, length = 60)
	private String username;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Result result;

	@Column(length = 64)
	private String ip;

	@Column(name = "ip_prefix", length = 64)
	private String ipPrefix;

	@Column(name = "new_location", nullable = false)
	private boolean newLocation;

	@Column(name = "user_agent", length = 400)
	private String userAgent;

	@Column(nullable = false)
	private Instant at;

	protected LoginEvent() {
	}

	public LoginEvent(Long userId, String username, Result result, String ip, String userAgent, Instant at,
			boolean newLocation) {
		this.userId = userId;
		this.username = truncate(username, 60);
		this.result = result;
		this.ip = truncate(ip, 64);
		this.ipPrefix = truncate(prefixOf(ip), 64);
		this.userAgent = truncate(userAgent, 400);
		this.at = at;
		this.newLocation = newLocation;
	}

	/**
	 * Network the address belongs to, used for the "new location" alert (FR 2.7, 3.8): first three IPv4 octets or
	 * first three IPv6 groups. A geo-IP lookup can replace this later without changing callers.
	 */
	public static String prefixOf(String ip) {
		if (ip == null || ip.isBlank()) {
			return null;
		}
		if (ip.contains(":")) {
			String[] groups = ip.split(":");
			return String.join(":", java.util.Arrays.copyOf(groups, Math.min(3, groups.length))) + "::/48";
		}
		int last = ip.lastIndexOf('.');
		return last > 0 ? ip.substring(0, last) + ".0/24" : ip;
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	public Result getResult() {
		return result;
	}

	public Long getUserId() {
		return userId;
	}

	public boolean isNewLocation() {
		return newLocation;
	}
}
