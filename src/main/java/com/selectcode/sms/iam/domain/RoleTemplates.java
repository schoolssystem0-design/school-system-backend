package com.selectcode.sms.iam.domain;

import static com.selectcode.sms.shared.security.Permissions.ACADEMICS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.ACADEMICS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.MESSAGES_SEND;
import static com.selectcode.sms.shared.security.Permissions.MESSAGES_TEMPLATES_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.MESSAGES_VIEW;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_CIRCULARS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_CIRCULARS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_COMPLAINTS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_COMPLAINTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_EVENTS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_EVENTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_TASKS;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_VISITORS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.OFFICE_VISITORS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.REPORTS_STAFF_VIEW;
import static com.selectcode.sms.shared.security.Permissions.REPORTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.AUDIT_VIEW;
import static com.selectcode.sms.shared.security.Permissions.IAM_LOGIN_HISTORY_VIEW;
import static com.selectcode.sms.shared.security.Permissions.IAM_ROLE_VIEW;
import static com.selectcode.sms.shared.security.Permissions.IAM_USER_VIEW;
import static com.selectcode.sms.shared.security.Permissions.SCHOOL_CALENDAR_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.SCHOOL_PROFILE_EDIT;
import static com.selectcode.sms.shared.security.Permissions.SCHOOL_PROFILE_VIEW;
import static com.selectcode.sms.shared.security.Permissions.SCHOOL_YEAR_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.SETTINGS_EDIT;
import static com.selectcode.sms.shared.security.Permissions.SETTINGS_VIEW;

import static com.selectcode.sms.shared.security.Permissions.ADMISSIONS_ENROL;
import static com.selectcode.sms.shared.security.Permissions.ADMISSIONS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.ADMISSIONS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.FAMILIES_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.FAMILIES_VIEW;
import static com.selectcode.sms.shared.security.Permissions.STAFF_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.STAFF_SALARY_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.STAFF_SALARY_VIEW;
import static com.selectcode.sms.shared.security.Permissions.STAFF_VIEW;
import static com.selectcode.sms.shared.security.Permissions.STUDENTS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.STUDENTS_MOVE;
import static com.selectcode.sms.shared.security.Permissions.STUDENTS_PRINT;
import static com.selectcode.sms.shared.security.Permissions.STUDENTS_PRIVATE_VIEW;
import static com.selectcode.sms.shared.security.Permissions.STUDENTS_VIEW;

import static com.selectcode.sms.shared.security.Permissions.FEES_ADJUST_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.FEES_ADJUST_REQUEST;
import static com.selectcode.sms.shared.security.Permissions.FEES_BILL_GENERATE;
import static com.selectcode.sms.shared.security.Permissions.FEES_DISCOUNT_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.FEES_DISCOUNT_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.FEES_PAYMENT_ADD;
import static com.selectcode.sms.shared.security.Permissions.FEES_PAYMENT_REVERSE;
import static com.selectcode.sms.shared.security.Permissions.FEES_REMINDERS_SEND;
import static com.selectcode.sms.shared.security.Permissions.FEES_REPORTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.FEES_SETUP;
import static com.selectcode.sms.shared.security.Permissions.FEES_VIEW;

import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_DAY_CLOSE;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_ENTRY_ADD;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_ENTRY_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_RECONCILE;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_REPORTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.ACCOUNTS_VIEW;
import static com.selectcode.sms.shared.security.Permissions.PAYROLL_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.PAYROLL_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.PAYROLL_PAY;
import static com.selectcode.sms.shared.security.Permissions.PAYROLL_VIEW;

import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_CORRECT;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_LEAVE_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_LEAVE_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_MARK;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_STAFF_APPROVE;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_STAFF_MARK;
import static com.selectcode.sms.shared.security.Permissions.ATTENDANCE_VIEW;

import static com.selectcode.sms.shared.security.Permissions.EXAMS_MANAGE;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_MARKS_ENTER;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_PRINT;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_PUBLISH;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_RESULTS_SEND;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_REVIEW;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_UNLOCK;
import static com.selectcode.sms.shared.security.Permissions.EXAMS_VIEW;

import com.selectcode.sms.shared.security.Permissions;
import java.util.List;
import java.util.Set;

/**
 * The roles every school starts with (FR 2 account types, DECISIONS D-13). Schools may edit them (except
 * Administrator) and create their own. When a later phase adds permissions, extend these templates: existing schools'
 * system roles receive the newly added permissions on startup ({@code SystemRoleUpgrader}).
 */
public final class RoleTemplates {

	public static final String ADMIN = "ADMIN";

	public record Template(String code, String name, String description, Set<String> permissions) {
	}

	private static final Set<String> VIEW_ALL = Set.of(SCHOOL_PROFILE_VIEW, IAM_USER_VIEW, IAM_ROLE_VIEW,
			IAM_LOGIN_HISTORY_VIEW, AUDIT_VIEW, SETTINGS_VIEW, ACADEMICS_VIEW, ADMISSIONS_VIEW, STUDENTS_VIEW,
			STUDENTS_PRIVATE_VIEW, FAMILIES_VIEW, STAFF_VIEW, STAFF_SALARY_VIEW, FEES_VIEW, FEES_REPORTS_VIEW, ACCOUNTS_VIEW, ACCOUNTS_REPORTS_VIEW, PAYROLL_VIEW, ATTENDANCE_VIEW, EXAMS_VIEW, OFFICE_COMPLAINTS_VIEW, OFFICE_CIRCULARS_VIEW, OFFICE_EVENTS_VIEW, OFFICE_VISITORS_VIEW, MESSAGES_VIEW, REPORTS_VIEW, REPORTS_STAFF_VIEW);

	/** Every staff member keeps tasks and reads circulars and the calendar (FR 3.5, 3.10, 3.11). */
	private static final Set<String> STAFF_COMMON = Set.of(OFFICE_TASKS, OFFICE_CIRCULARS_VIEW, OFFICE_EVENTS_VIEW);

	public static final List<Template> ALL = List.of(
			new Template(ADMIN, "Administrator", "Full set-up, users, settings and all modules", Permissions.CODES),
			new Template("PRINCIPAL", "Principal", "All academic and administrative information, approvals, reports",
					union(VIEW_ALL, Set.of(SCHOOL_PROFILE_EDIT, SCHOOL_YEAR_MANAGE, SCHOOL_CALENDAR_MANAGE,
							SETTINGS_EDIT, ACADEMICS_MANAGE, ADMISSIONS_MANAGE, ADMISSIONS_ENROL, STUDENTS_MANAGE,
							STUDENTS_MOVE, STUDENTS_PRINT, FAMILIES_MANAGE, STAFF_MANAGE, FEES_DISCOUNT_MANAGE,
							FEES_DISCOUNT_APPROVE, FEES_ADJUST_APPROVE, FEES_REMINDERS_SEND, ACCOUNTS_ENTRY_APPROVE,
							PAYROLL_APPROVE, ATTENDANCE_MARK, ATTENDANCE_CORRECT, ATTENDANCE_LEAVE_MANAGE,
							ATTENDANCE_LEAVE_APPROVE, ATTENDANCE_STAFF_MARK, ATTENDANCE_STAFF_APPROVE, EXAMS_MANAGE,
							EXAMS_MARKS_ENTER, EXAMS_REVIEW, EXAMS_PUBLISH, EXAMS_UNLOCK, EXAMS_PRINT, EXAMS_RESULTS_SEND, OFFICE_TASKS,
							OFFICE_COMPLAINTS_MANAGE, OFFICE_CIRCULARS_MANAGE, OFFICE_EVENTS_MANAGE, OFFICE_VISITORS_MANAGE,
							MESSAGES_SEND, MESSAGES_TEMPLATES_MANAGE))),
			new Template("SCHOOL_OWNER", "School owner", "Read-only view of finance, reports and the dashboard",
					union(VIEW_ALL, STAFF_COMMON)),
			new Template("AUDITOR", "Auditor", "Read-only finance and activity history",
					Set.of(AUDIT_VIEW, IAM_LOGIN_HISTORY_VIEW, SCHOOL_PROFILE_VIEW, SETTINGS_VIEW, STUDENTS_VIEW,
							FAMILIES_VIEW, STAFF_VIEW, STAFF_SALARY_VIEW, FEES_VIEW, FEES_REPORTS_VIEW, ACCOUNTS_VIEW,
							ACCOUNTS_REPORTS_VIEW, PAYROLL_VIEW)),
			new Template("ACCOUNTANT", "Accounts staff", "Fees, receipts, school accounts, salary records",
					union(STAFF_COMMON, Set.of(SCHOOL_PROFILE_VIEW, SETTINGS_VIEW, ACADEMICS_VIEW, STUDENTS_VIEW, STUDENTS_PRIVATE_VIEW,
							FAMILIES_VIEW, STAFF_VIEW, STAFF_SALARY_VIEW, STAFF_SALARY_MANAGE, FEES_VIEW, FEES_SETUP,
							FEES_DISCOUNT_MANAGE, FEES_DISCOUNT_APPROVE, FEES_BILL_GENERATE, FEES_PAYMENT_ADD,
							FEES_PAYMENT_REVERSE, FEES_ADJUST_REQUEST, FEES_ADJUST_APPROVE, FEES_REMINDERS_SEND,
							FEES_REPORTS_VIEW, ACCOUNTS_VIEW, ACCOUNTS_MANAGE, ACCOUNTS_ENTRY_ADD, ACCOUNTS_DAY_CLOSE,
							ACCOUNTS_RECONCILE, ACCOUNTS_REPORTS_VIEW, PAYROLL_VIEW, PAYROLL_MANAGE, PAYROLL_PAY,
							ATTENDANCE_VIEW, MESSAGES_VIEW))),
			new Template("FEE_CLERK", "Fee clerk",
					"Manual fee collection only: set up fees, bill, record payments, status and reports (no school accounts or payroll)",
					union(STAFF_COMMON, Set.of(SCHOOL_PROFILE_VIEW, SETTINGS_VIEW, ACADEMICS_VIEW, STUDENTS_VIEW,
							STUDENTS_PRIVATE_VIEW, FAMILIES_VIEW, FEES_VIEW, FEES_SETUP, FEES_DISCOUNT_MANAGE,
							FEES_DISCOUNT_APPROVE, FEES_BILL_GENERATE, FEES_PAYMENT_ADD, FEES_PAYMENT_REVERSE,
							FEES_ADJUST_REQUEST, FEES_ADJUST_APPROVE, FEES_REPORTS_VIEW))),
			new Template("TEACHER", "Teacher", "Own classes: attendance, marks, diary, timetable",
					union(STAFF_COMMON, Set.of(ACADEMICS_VIEW, STUDENTS_VIEW, ATTENDANCE_VIEW, ATTENDANCE_MARK, EXAMS_VIEW, EXAMS_MARKS_ENTER, REPORTS_VIEW))),
			new Template("FRONT_OFFICE", "Front office", "Enquiries, visitors, basic student look-up",
					union(STAFF_COMMON, Set.of(SCHOOL_PROFILE_VIEW, ACADEMICS_VIEW, ADMISSIONS_VIEW, ADMISSIONS_MANAGE, ADMISSIONS_ENROL,
							STUDENTS_VIEW, STUDENTS_PRIVATE_VIEW, STUDENTS_MANAGE, STUDENTS_PRINT, FAMILIES_VIEW,
							FAMILIES_MANAGE, ATTENDANCE_VIEW, ATTENDANCE_LEAVE_MANAGE, ATTENDANCE_STAFF_MARK, OFFICE_COMPLAINTS_VIEW,
							OFFICE_COMPLAINTS_MANAGE, OFFICE_VISITORS_VIEW, OFFICE_VISITORS_MANAGE, MESSAGES_VIEW, MESSAGES_SEND))),
			new Template("EXAM_COORDINATOR", "Exam coordinator", "Exams, marks, results and report cards",
					union(STAFF_COMMON, Set.of(ACADEMICS_VIEW, ACADEMICS_MANAGE, SETTINGS_VIEW, STUDENTS_VIEW, STAFF_VIEW, EXAMS_VIEW, EXAMS_MANAGE,
							EXAMS_MARKS_ENTER, EXAMS_REVIEW, EXAMS_PRINT, REPORTS_VIEW))),
			new Template("WEBSITE_EDITOR", "Website editor", "School website content and online forms",
					union(STAFF_COMMON, Set.of(SCHOOL_PROFILE_VIEW))));

	private RoleTemplates() {
	}

	private static Set<String> union(Set<String> a, Set<String> b) {
		var all = new java.util.HashSet<>(a);
		all.addAll(b);
		return Set.copyOf(all);
	}
}
