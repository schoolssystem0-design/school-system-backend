package com.selectcode.sms.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Arranges data directly as the superuser (bypassing RLS and the app), so tests can set up two schools and then check
 * what the application is allowed to see.
 */
public final class TestData {

	/** Low cost factor: test speed only. Production uses 12. */
	private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

	private TestData() {
	}

	public record SeededSchool(long id, String code) {
	}

	public static SeededSchool school(String prefix) {
		String code = (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		JdbcTemplate jdbc = TestDatabase.superuser();
		Long id = jdbc.queryForObject("insert into school (code, name) values (?, ?) returning id", Long.class, code,
				prefix + " School");
		jdbc.update("insert into school_profile (school_id, name) values (?, ?)", id, prefix + " School");
		jdbc.update("""
				insert into enquiry_status (school_id, name, colour, display_order, system_code) values
				(?, 'New enquiry', '#2563eb', 1, 'NEW'), (?, 'Follow-up needed', '#d97706', 2, 'FOLLOW_UP'),
				(?, 'Admission approved', '#16a34a', 3, 'APPROVED'), (?, 'Not proceeding', '#6b7280', 4, 'NOT_PROCEEDING'),
				(?, 'Enrolled', '#7c3aed', 5, 'ENROLLED')""", id, id, id, id, id);
		// Fees defaults (FeeLedger.onSchoolCreated does the same for schools created through the platform).
		jdbc.update("""
				insert into ledger_account (school_id, code, name, account_type, subtype, display_order) values
				(?, '1000', 'Cash in hand', 'ASSET', 'CASH', 1), (?, '1010', 'Bank account', 'ASSET', 'BANK', 2),
				(?, '2100', 'Fees received in advance', 'LIABILITY', 'STUDENT_ADVANCE', 3),
				(?, '4000', 'Fee income', 'INCOME', 'FEE_INCOME', 4)""", id, id, id, id);
		jdbc.update("""
				insert into fee_head (school_id, name, frequency, display_order, system_code)
				values (?, 'Late fee', 'CUSTOM', 1000, 'LATE_FEE'), (?, 'Absent fine', 'CUSTOM', 1001, 'ABSENT_FINE')""", id, id);
		com.selectcode.sms.accounts.app.AccountsSetupService.seed(jdbc, id);
		com.selectcode.sms.exams.app.ExamSetupService.seed(jdbc, id);
		return new SeededSchool(id, code);
	}

	/** A user without any role (no permissions). */
	public static long user(SeededSchool school, String username, String password) {
		return TestDatabase.superuser()
			.queryForObject("""
					insert into app_user (school_id, username, display_name, password_hash, email)
					values (?, ?, ?, ?, ?) returning id""", Long.class, school.id(), username, "User " + username,
					ENCODER.encode(password), username + "@" + school.code().toLowerCase() + ".test");
	}

	/** A user with the built-in Administrator role (every permission). */
	public static long admin(SeededSchool school, String username, String password) {
		long userId = user(school, username, password);
		grantRole(school, userId, "ADMIN");
		return userId;
	}

	/** A user with a custom role holding exactly these permissions. */
	public static long userWithPermissions(SeededSchool school, String username, String password,
			String... permissions) {
		long userId = user(school, username, password);
		String code = ("R_" + username).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
		long roleId = role(school, code);
		JdbcTemplate jdbc = TestDatabase.superuser();
		for (String permission : permissions) {
			jdbc.update("insert into role_permission (school_id, role_id, permission) values (?, ?, ?)", school.id(),
					roleId, permission);
		}
		jdbc.update("insert into user_role (school_id, user_id, role_id) values (?, ?, ?)", school.id(), userId, roleId);
		return userId;
	}

	public static void grantRole(SeededSchool school, long userId, String roleCode) {
		long roleId = role(school, roleCode);
		TestDatabase.superuser()
			.update("insert into user_role (school_id, user_id, role_id) values (?, ?, ?) on conflict do nothing",
					school.id(), userId, roleId);
	}

	private static long role(SeededSchool school, String code) {
		return TestDatabase.superuser().queryForObject("""
				insert into role (school_id, code, name, system_role) values (?, ?, ?, ?)
				on conflict (school_id, code) do update set name = excluded.name returning id""", Long.class,
				school.id(), code, code, "ADMIN".equals(code));
	}

	/** An academic year around today (30 days ago to ~10 months ahead), optionally the current one. */
	public static long yearAroundToday(SeededSchool school, boolean current) {
		java.time.LocalDate start = java.time.LocalDate.now().minusDays(30);
		return year(school, start.getYear() + "-" + (start.getYear() + 1), start, start.plusDays(330), current);
	}

	public static long year(SeededSchool school, String name, java.time.LocalDate start, java.time.LocalDate end,
			boolean current) {
		return TestDatabase.superuser().queryForObject("""
				insert into academic_year (school_id, name, start_date, end_date, status) values (?, ?, ?, ?, ?)
				returning id""", Long.class, school.id(), name, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end),
				current ? "CURRENT" : "UPCOMING");
	}

	public static long schoolClass(SeededSchool school, String name, int order) {
		return TestDatabase.superuser().queryForObject("""
				insert into school_class (school_id, name, short_name, display_order) values (?, ?, ?, ?) returning id""",
				Long.class, school.id(), name, name.replace("Class ", ""), order);
	}

	public static long section(SeededSchool school, long classId, String name) {
		return TestDatabase.superuser().queryForObject("""
				insert into section (school_id, class_id, name) values (?, ?, ?) returning id""", Long.class, school.id(),
				classId, name);
	}

	public static long subject(SeededSchool school, String name) {
		return TestDatabase.superuser().queryForObject("""
				insert into subject (school_id, name, short_name) values (?, ?, ?) returning id""", Long.class,
				school.id(), name, name.substring(0, Math.min(4, name.length())).toUpperCase());
	}

	public static void classSubject(SeededSchool school, long yearId, long classId, long subjectId) {
		TestDatabase.superuser().update("""
				insert into class_subject (school_id, academic_year_id, class_id, subject_id, max_marks, display_order)
				values (?, ?, ?, ?, 100, 1)""", school.id(), yearId, classId, subjectId);
	}

	public static long teacher(SeededSchool school, String name) {
		return TestDatabase.superuser().queryForObject("""
				insert into staff (school_id, employee_no, category, name, designation, joining_date)
				values (?, ?, 'TEACHER', ?, 'Teacher', current_date - 400) returning id""", Long.class, school.id(),
				"T-" + UUID.randomUUID().toString().substring(0, 6), name);
	}

	public static void suspend(SeededSchool school) {
		TestDatabase.superuser().update("update school set status = 'SUSPENDED' where id = ?", school.id());
	}

	/** A super admin with an enrolled TOTP secret. */
	public static long superAdmin(String email, String password, String totpSecret) {
		return TestDatabase.superuser().queryForObject("""
				insert into platform_user (email, name, password_hash, totp_secret) values (?, 'Owner', ?, ?)
				returning id""", Long.class, email, ENCODER.encode(password), totpSecret);
	}
}
