package com.selectcode.sms.shared.security;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The permission catalogue (FR 1.7, 2.5). Codes are {@code module.resource.action}. Constants are compile-time strings
 * so they can be used in {@code @PreAuthorize("hasAuthority('" + Permissions.X + "')")}.
 *
 * <p>When a phase adds permissions, add them here with the next {@code since} version and add them to the role
 * templates in {@code iam.domain.RoleTemplates}; existing schools' system roles are upgraded on startup.
 */
public final class Permissions {

	public static final String SCHOOL_PROFILE_VIEW = "school.profile.view";

	public static final String SCHOOL_PROFILE_EDIT = "school.profile.edit";

	public static final String SCHOOL_YEAR_MANAGE = "school.year.manage";

	public static final String SCHOOL_YEAR_CORRECT_CLOSED = "school.year.correct_closed";

	public static final String SCHOOL_CALENDAR_MANAGE = "school.calendar.manage";

	public static final String IAM_USER_VIEW = "iam.user.view";

	public static final String IAM_USER_MANAGE = "iam.user.manage";

	public static final String IAM_ROLE_VIEW = "iam.role.view";

	public static final String IAM_ROLE_MANAGE = "iam.role.manage";

	public static final String IAM_LOGIN_HISTORY_VIEW = "iam.login_history.view";

	public static final String AUDIT_VIEW = "audit.view";

	public static final String SETTINGS_VIEW = "settings.view";

	public static final String SETTINGS_EDIT = "settings.edit";

	public static final String ACADEMICS_VIEW = "academics.view";

	public static final String ACADEMICS_MANAGE = "academics.manage";

	// ---- Phase 2: admissions, students, families, staff
	public static final String ADMISSIONS_VIEW = "admissions.view";

	public static final String ADMISSIONS_MANAGE = "admissions.manage";

	public static final String ADMISSIONS_ENROL = "admissions.enrol";

	public static final String STUDENTS_VIEW = "students.view";

	public static final String STUDENTS_MANAGE = "students.manage";

	public static final String STUDENTS_MOVE = "students.move";

	public static final String STUDENTS_PRIVATE_VIEW = "students.private.view";

	public static final String STUDENTS_PRINT = "students.print";

	public static final String FAMILIES_VIEW = "families.view";

	public static final String FAMILIES_MANAGE = "families.manage";

	public static final String STAFF_VIEW = "staff.view";

	public static final String STAFF_MANAGE = "staff.manage";

	public static final String STAFF_SALARY_VIEW = "staff.salary.view";

	public static final String STAFF_SALARY_MANAGE = "staff.salary.manage";

	// ---- Phase 3: fees
	public static final String FEES_VIEW = "fees.view";

	public static final String FEES_SETUP = "fees.setup";

	public static final String FEES_DISCOUNT_MANAGE = "fees.discount.manage";

	public static final String FEES_DISCOUNT_APPROVE = "fees.discount.approve";

	public static final String FEES_BILL_GENERATE = "fees.bill.generate";

	public static final String FEES_PAYMENT_ADD = "fees.payment.add";

	public static final String FEES_PAYMENT_REVERSE = "fees.payment.reverse";

	public static final String FEES_ADJUST_REQUEST = "fees.adjust.request";

	public static final String FEES_ADJUST_APPROVE = "fees.adjust.approve";

	public static final String FEES_REMINDERS_SEND = "fees.reminders.send";

	public static final String FEES_REPORTS_VIEW = "fees.reports.view";

	// ---- Phase 4: school accounts and payroll
	public static final String ACCOUNTS_VIEW = "accounts.view";

	public static final String ACCOUNTS_MANAGE = "accounts.manage";

	public static final String ACCOUNTS_ENTRY_ADD = "accounts.entry.add";

	public static final String ACCOUNTS_ENTRY_APPROVE = "accounts.entry.approve";

	public static final String ACCOUNTS_DAY_CLOSE = "accounts.day.close";

	public static final String ACCOUNTS_RECONCILE = "accounts.reconcile";

	public static final String ACCOUNTS_REPORTS_VIEW = "accounts.reports.view";

	public static final String PAYROLL_VIEW = "payroll.view";

	public static final String PAYROLL_MANAGE = "payroll.manage";

	public static final String PAYROLL_APPROVE = "payroll.approve";

	public static final String PAYROLL_PAY = "payroll.pay";

	// ---- Phase 5: attendance
	public static final String ATTENDANCE_VIEW = "attendance.view";

	public static final String ATTENDANCE_MARK = "attendance.mark";

	public static final String ATTENDANCE_CORRECT = "attendance.correct";

	public static final String ATTENDANCE_LEAVE_MANAGE = "attendance.leave.manage";

	public static final String ATTENDANCE_LEAVE_APPROVE = "attendance.leave.approve";

	public static final String ATTENDANCE_STAFF_MARK = "attendance.staff.mark";

	public static final String ATTENDANCE_STAFF_APPROVE = "attendance.staff.approve";

	// ---- Phase 6: examinations and results
	public static final String EXAMS_VIEW = "exams.view";

	public static final String EXAMS_MANAGE = "exams.manage";

	public static final String EXAMS_MARKS_ENTER = "exams.marks.enter";

	public static final String EXAMS_REVIEW = "exams.review";

	public static final String EXAMS_PUBLISH = "exams.publish";

	public static final String EXAMS_UNLOCK = "exams.unlock";

	public static final String EXAMS_PRINT = "exams.print";

	public static final String EXAMS_RESULTS_SEND = "exams.results.send";

	public static final String OFFICE_TASKS = "office.tasks";

	public static final String OFFICE_COMPLAINTS_VIEW = "office.complaints.view";

	public static final String OFFICE_COMPLAINTS_MANAGE = "office.complaints.manage";

	public static final String OFFICE_CIRCULARS_VIEW = "office.circulars.view";

	public static final String OFFICE_CIRCULARS_MANAGE = "office.circulars.manage";

	public static final String OFFICE_EVENTS_VIEW = "office.events.view";

	public static final String OFFICE_EVENTS_MANAGE = "office.events.manage";

	public static final String OFFICE_VISITORS_VIEW = "office.visitors.view";

	public static final String OFFICE_VISITORS_MANAGE = "office.visitors.manage";

	public static final String MESSAGES_VIEW = "messages.view";

	public static final String MESSAGES_SEND = "messages.send";

	public static final String MESSAGES_TEMPLATES_MANAGE = "messages.templates.manage";

	public static final String REPORTS_VIEW = "reports.view";

	public static final String REPORTS_STAFF_VIEW = "reports.staff.view";

	public record Definition(String code, String group, String description, int since) {
	}

	public static final List<Definition> ALL = List.of(
			new Definition(SCHOOL_PROFILE_VIEW, "School setup", "View school profile and document identity", 1),
			new Definition(SCHOOL_PROFILE_EDIT, "School setup", "Edit school profile, logo, stamp and signatures", 1),
			new Definition(SCHOOL_YEAR_MANAGE, "School setup", "Create, open and close academic years", 1),
			new Definition(SCHOOL_YEAR_CORRECT_CLOSED, "School setup", "Correct records in a closed academic year", 1),
			new Definition(SCHOOL_CALENDAR_MANAGE, "School setup", "Set working days and holidays", 1),
			new Definition(IAM_USER_VIEW, "Users & security", "View user accounts", 1),
			new Definition(IAM_USER_MANAGE, "Users & security",
					"Add, edit, activate/deactivate users and reset passwords", 1),
			new Definition(IAM_ROLE_VIEW, "Users & security", "View roles and their permissions", 1),
			new Definition(IAM_ROLE_MANAGE, "Users & security", "Create and change roles and permissions", 1),
			new Definition(IAM_LOGIN_HISTORY_VIEW, "Users & security", "View login history and failed attempts", 1),
			new Definition(AUDIT_VIEW, "Users & security", "View activity history (audit trail)", 1),
			new Definition(SETTINGS_VIEW, "Rules & settings", "View school rules and settings", 1),
			new Definition(SETTINGS_EDIT, "Rules & settings", "Change school rules and settings", 1),
			new Definition(ACADEMICS_VIEW, "Classes & subjects", "View classes, sections and subjects", 1),
			new Definition(ACADEMICS_MANAGE, "Classes & subjects",
					"Manage classes, sections, subjects, maximum marks, teaching assignments and timetable", 1),
			new Definition(ADMISSIONS_VIEW, "Admissions", "View admission enquiries", 2),
			new Definition(ADMISSIONS_MANAGE, "Admissions",
					"Add and follow up enquiries, statuses, entry tests, terms and syllabus", 2),
			new Definition(ADMISSIONS_ENROL, "Admissions", "Enrol students (create the student record)", 2),
			new Definition(STUDENTS_VIEW, "Students", "View students (limited to assigned classes for teachers)", 2),
			new Definition(STUDENTS_MANAGE, "Students", "Edit student details, documents, activities, bulk changes", 2),
			new Definition(STUDENTS_MOVE, "Students", "Move section/class, promote, repeat and withdraw students", 2),
			new Definition(STUDENTS_PRIVATE_VIEW, "Students",
					"See private details: phone numbers, address, CNIC/B-Form, documents", 2),
			new Definition(STUDENTS_PRINT, "Students", "Print ID cards, certificates and student records", 2),
			new Definition(FAMILIES_VIEW, "Families", "View family records and siblings", 2),
			new Definition(FAMILIES_MANAGE, "Families", "Add and edit families, guardians and contacts", 2),
			new Definition(STAFF_VIEW, "Staff", "View teacher and staff records", 2),
			new Definition(STAFF_MANAGE, "Staff", "Add and edit staff, documents, logins, leaving", 2),
			new Definition(STAFF_SALARY_VIEW, "Staff", "View salary details", 2),
			new Definition(STAFF_SALARY_MANAGE, "Staff", "Change salary details and increments", 2),
			new Definition(FEES_VIEW, "Fees", "View fee set-up, bills, ledgers, payments and balances", 3),
			new Definition(FEES_SETUP, "Fees", "Set fee heads, class amounts, student amounts and sibling rules", 3),
			new Definition(FEES_DISCOUNT_MANAGE, "Fees", "Give and end discounts and scholarships", 3),
			new Definition(FEES_DISCOUNT_APPROVE, "Fees", "Approve discounts above the no-approval limit", 3),
			new Definition(FEES_BILL_GENERATE, "Fees", "Generate bills, late fees and vouchers", 3),
			new Definition(FEES_PAYMENT_ADD, "Fees", "Receive fee payments and print receipts", 3),
			new Definition(FEES_PAYMENT_REVERSE, "Fees", "Reverse a fee payment (with a reason)", 3),
			new Definition(FEES_ADJUST_REQUEST, "Fees", "Request corrections and write-offs of charged fees", 3),
			new Definition(FEES_ADJUST_APPROVE, "Fees", "Approve fee corrections and write-offs", 3),
			new Definition(FEES_REMINDERS_SEND, "Fees", "Send fee reminders, letters and record follow-ups", 3),
			new Definition(FEES_REPORTS_VIEW, "Fees", "View fee reports, defaulters and collection totals", 3),
			new Definition(ACCOUNTS_VIEW, "School accounts", "View cash and bank accounts, ledgers and entries", 4),
			new Definition(ACCOUNTS_MANAGE, "School accounts", "Add accounts, opening balances, categories and suppliers", 4),
			new Definition(ACCOUNTS_ENTRY_ADD, "School accounts", "Record income, expenses, transfers and supplier bills", 4),
			new Definition(ACCOUNTS_ENTRY_APPROVE, "School accounts", "Approve large expenses and reverse entries", 4),
			new Definition(ACCOUNTS_DAY_CLOSE, "School accounts", "Close the day (count cash against the books)", 4),
			new Definition(ACCOUNTS_RECONCILE, "School accounts", "Mark bank entries as cleared", 4),
			new Definition(ACCOUNTS_REPORTS_VIEW, "School accounts", "View financial reports and the trial balance", 4),
			new Definition(PAYROLL_VIEW, "Payroll", "View salary sheets and slips", 4),
			new Definition(PAYROLL_MANAGE, "Payroll", "Prepare salary sheets (attendance, allowances, deductions)", 4),
			new Definition(PAYROLL_APPROVE, "Payroll", "Approve and reverse salary sheets", 4),
			new Definition(PAYROLL_PAY, "Payroll", "Pay approved salaries from a cash or bank account", 4),
			new Definition(ATTENDANCE_VIEW, "Attendance", "View registers, percentages and attendance reports", 5),
			new Definition(ATTENDANCE_MARK, "Attendance", "Take the daily class register (teachers: own classes)", 5),
			new Definition(ATTENDANCE_CORRECT, "Attendance",
					"Correct past attendance, bulk changes and marking on holidays (with a reason)", 5),
			new Definition(ATTENDANCE_LEAVE_MANAGE, "Attendance", "Enter leave applications for students and staff", 5),
			new Definition(ATTENDANCE_LEAVE_APPROVE, "Attendance", "Approve or reject leave applications", 5),
			new Definition(ATTENDANCE_STAFF_MARK, "Attendance", "Record daily staff attendance and arrival times", 5),
			new Definition(ATTENDANCE_STAFF_APPROVE, "Attendance", "Approve monthly staff attendance for payroll", 5),
			new Definition(EXAMS_VIEW, "Exams & results", "View exams, datesheets and results (teachers: visible exams only)", 6),
			new Definition(EXAMS_MANAGE, "Exams & results", "Create exams, papers, datesheets and grade scales", 6),
			new Definition(EXAMS_MARKS_ENTER, "Exams & results", "Enter marks (teachers: their own subjects and sections)", 6),
			new Definition(EXAMS_REVIEW, "Exams & results", "Mark a class's results as reviewed, enter overall remarks", 6),
			new Definition(EXAMS_PUBLISH, "Exams & results", "Publish results", 6),
			new Definition(EXAMS_UNLOCK, "Exams & results", "Unlock published results for correction (with a reason)", 6),
			new Definition(EXAMS_PRINT, "Exams & results", "Print report cards, result sheets and admit cards", 6),
			new Definition(EXAMS_RESULTS_SEND, "Exams & results", "Send results to parents by SMS", 6),
			new Definition(OFFICE_TASKS, "Office", "Keep a task list and share tasks with colleagues", 7),
			new Definition(OFFICE_COMPLAINTS_VIEW, "Office", "View parent complaints", 7),
			new Definition(OFFICE_COMPLAINTS_MANAGE, "Office", "Record, assign and close parent complaints", 7),
			new Definition(OFFICE_CIRCULARS_VIEW, "Office", "Read circulars", 7),
			new Definition(OFFICE_CIRCULARS_MANAGE, "Office", "Post and remove circulars", 7),
			new Definition(OFFICE_EVENTS_VIEW, "Office", "View the events calendar", 7),
			new Definition(OFFICE_EVENTS_MANAGE, "Office", "Add and remove calendar events", 7),
			new Definition(OFFICE_VISITORS_VIEW, "Office", "View the visitor log", 7),
			new Definition(OFFICE_VISITORS_MANAGE, "Office", "Record visitors", 7),
			new Definition(MESSAGES_VIEW, "Messages", "View the message delivery log", 7),
			new Definition(MESSAGES_SEND, "Messages", "Send messages to families, enquiries and staff", 7),
			new Definition(MESSAGES_TEMPLATES_MANAGE, "Messages", "Change the wording of automatic messages", 7),
			new Definition(REPORTS_VIEW, "Reports", "Student and class weekly and monthly reports (teachers: their own sections)", 8),
			new Definition(REPORTS_STAFF_VIEW, "Reports", "Teacher weekly and monthly reports", 8));

	/** Highest {@code since} in the catalogue; system roles below this version get new permissions on startup. */
	public static final int CATALOGUE_VERSION = ALL.stream().mapToInt(Definition::since).max().orElse(0);

	public static final Set<String> CODES = ALL.stream().map(Definition::code).collect(Collectors.toUnmodifiableSet());

	private Permissions() {
	}

	public static boolean exists(String code) {
		return CODES.contains(code);
	}

	public static List<String> codesSince(int versionExclusive) {
		return ALL.stream().filter(d -> d.since() > versionExclusive).map(Definition::code).toList();
	}

	static {
		if (CODES.size() != ALL.size()) {
			throw new IllegalStateException("Duplicate permission code in catalogue: " + Arrays.toString(ALL.toArray()));
		}
	}
}
