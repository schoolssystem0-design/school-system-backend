package com.selectcode.sms.students.app;

import com.selectcode.sms.academics.AcademicStructure;
import com.selectcode.sms.families.FamilyDetails;
import com.selectcode.sms.families.FamilyService;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.validation.PakFormats;
import com.selectcode.sms.shared.web.ApiException;
import com.selectcode.sms.shared.year.YearContext;
import com.selectcode.sms.students.StudentDetails;
import com.selectcode.sms.students.StudentService;
import com.selectcode.sms.students.StudentService.Admission;
import com.selectcode.sms.students.StudentService.StudentView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admits many students into one section from an Excel sheet. All or nothing: every row is checked first (required
 * fields, formats, duplicates); if any row has a problem nothing is admitted and every problem is listed by row. Each
 * row is then admitted exactly like the New admission form, so GR numbers and family rules are the same. Brothers and
 * sisters are put in one family by the father's mobile, also with a family that is already in the school.
 */
@Service
public class StudentImport {

	public static final int MAX_ROWS = 500;

	/** One column of the sheet. Headings are matched without "*", case or spaces, so the order may change. */
	private record Column(String key, String heading, boolean required, String example) {
	}

	private static final List<Column> COLUMNS = List.of(
			new Column("name", "Student name", true, "Hamza Ali"),
			new Column("gender", "Gender", true, "Male"),
			new Column("dob", "Date of birth", true, "14-03-2016"),
			new Column("fatherName", "Father's name", true, "Ali Raza"),
			new Column("fatherPhone", "Father's mobile", true, "0300-1234567"),
			new Column("motherName", "Mother's name", false, "Sana Ali"),
			new Column("motherPhone", "Mother's mobile", false, "0301-7654321"),
			new Column("fatherCnic", "Father's CNIC", false, "35202-1234567-1"),
			new Column("bForm", "B-Form no.", false, "35202-7654321-2"),
			new Column("religion", "Religion", false, "Islam"),
			new Column("placeOfBirth", "Place of birth", false, "Lahore"),
			new Column("address", "Home address", false, "12 Canal Road, Lahore"),
			new Column("previousSchool", "Previous school", false, ""));

	public record ImportRow(int row, String name, String gender, LocalDate dob, String fatherName, String fatherPhone,
			String family, List<String> problems) {
	}

	public record ImportCheck(String section, LocalDate admissionDate, int students, int rowsWithProblems,
			List<String> fileProblems, List<ImportRow> rows, boolean ready) {
	}

	public record ImportedStudent(int row, long id, String grNo, String name) {
	}

	public record ImportResult(String section, int admitted, List<ImportedStudent> students) {
	}

	/** A checked row ready to admit. */
	private record Planned(ImportRow view, StudentDetails student, FamilyDetails family, Long existingFamilyId) {
	}

	private record Checked(ImportCheck check, List<Planned> planned) {
	}

	private final StudentService students;

	private final FamilyService families;

	private final AcademicStructure structure;

	private final YearContext yearContext;

	private final StudentAccess access;

	private final AuditService audit;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	StudentImport(StudentService students, FamilyService families, AcademicStructure structure, YearContext yearContext,
			StudentAccess access, AuditService audit, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.students = students;
		this.families = families;
		this.structure = structure;
		this.yearContext = yearContext;
		this.access = access;
		this.audit = audit;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	// ---------------------------------------------------------------- template

	/** The Excel template: the headings (required ones marked *), a Gender list, and a sheet explaining each column. */
	public byte[] template() {
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = book.createSheet("Students");
			CellStyle required = headingStyle(book, IndexedColors.LIGHT_ORANGE);
			CellStyle optional = headingStyle(book, IndexedColors.GREY_25_PERCENT);
			CellStyle text = book.createCellStyle();
			text.setDataFormat(book.createDataFormat().getFormat("@"));
			Row heading = sheet.createRow(0);
			for (int i = 0; i < COLUMNS.size(); i++) {
				Column c = COLUMNS.get(i);
				Cell cell = heading.createCell(i);
				cell.setCellValue(c.heading() + (c.required() ? " *" : ""));
				cell.setCellStyle(c.required() ? required : optional);
				sheet.setColumnWidth(i, Math.max(16, c.heading().length() + 6) * 256);
				// Typed as text so Excel keeps leading zeros and does not turn numbers into 3.52E+12
				sheet.setDefaultColumnStyle(i, text);
			}
			sheet.createFreezePane(0, 1);
			DataValidationHelper helper = sheet.getDataValidationHelper();
			var gender = helper.createValidation(helper.createExplicitListConstraint(new String[] { "Male", "Female" }),
					new CellRangeAddressList(1, MAX_ROWS, 1, 1));
			gender.setShowErrorBox(true);
			sheet.addValidationData(gender);

			Sheet help = book.createSheet("How to fill");
			help.setColumnWidth(0, 22 * 256);
			help.setColumnWidth(1, 14 * 256);
			help.setColumnWidth(2, 24 * 256);
			help.setColumnWidth(3, 70 * 256);
			Row h = help.createRow(0);
			String[] titles = { "Column", "Required?", "Example", "Notes" };
			for (int i = 0; i < titles.length; i++) {
				h.createCell(i).setCellValue(titles[i]);
				h.getCell(i).setCellStyle(optional);
			}
			for (int i = 0; i < COLUMNS.size(); i++) {
				Column c = COLUMNS.get(i);
				Row r = help.createRow(i + 1);
				r.createCell(0).setCellValue(c.heading());
				r.createCell(1).setCellValue(c.required() ? "Required" : "Optional");
				r.createCell(2).setCellValue(c.example());
				r.createCell(3).setCellValue(note(c.key()));
			}
			Row last = help.createRow(COLUMNS.size() + 2);
			last.createCell(0).setCellValue("One student per row on the Students sheet, from row 2. Up to " + MAX_ROWS
					+ " rows. GR numbers are given automatically. If any required field is missing, nothing is imported "
					+ "and every problem is shown by row number.");
			book.write(out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String note(String key) {
		return switch (key) {
			case "gender" -> "Male or Female (Boy, Girl, M and F also work)";
			case "dob" -> "Day-month-year, e.g. 14-03-2016 or 14/03/2016, or an Excel date";
			case "fatherPhone" -> "Pakistani mobile, e.g. 0300-1234567. Children with the same father's mobile become one family";
			case "motherPhone" -> "Pakistani mobile, e.g. 0301-7654321";
			case "fatherCnic", "bForm" -> "13 digits, with or without dashes";
			default -> "";
		};
	}

	private static CellStyle headingStyle(Workbook book, IndexedColors color) {
		CellStyle style = book.createCellStyle();
		Font bold = book.createFont();
		bold.setBold(true);
		style.setFont(bold);
		style.setFillForegroundColor(color.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		return style;
	}

	// ---------------------------------------------------------------- check and import

	/** Reads and checks the sheet; admits nothing. */
	public ImportCheck check(InputStream file, long sectionId, LocalDate admissionDate) {
		return checked(file, sectionId, admissionDate).check();
	}

	/** Admits every row in one go, or nobody when any row has a problem. */
	public ImportResult importStudents(InputStream file, long sectionId, LocalDate admissionDate) {
		Checked checked = checked(file, sectionId, admissionDate);
		ImportCheck check = checked.check();
		if (!check.ready()) {
			int bad = check.rowsWithProblems();
			throw ApiException.businessRule("IMPORT_INVALID", (check.fileProblems().isEmpty()
					? bad + (bad == 1 ? " row has" : " rows have") + " problems"
					: check.fileProblems().getFirst()) + ". Nothing was imported: fix the sheet and upload it again.");
		}
		return transaction.execute(tx -> {
			Map<String, Long> familyByPhone = new HashMap<>();
			List<ImportedStudent> admitted = new ArrayList<>();
			for (Planned p : checked.planned()) {
				String phone = p.family().fatherPhone();
				Long familyId = p.existingFamilyId() != null ? p.existingFamilyId() : familyByPhone.get(phone);
				StudentView view = students.admit(new Admission(p.student(), familyId, familyId == null ? p.family() : null,
						sectionId, admissionDate, null));
				familyByPhone.putIfAbsent(phone, view.family().id());
				admitted.add(new ImportedStudent(p.view().row(), view.id(), view.grNo(), view.details().name()));
			}
			audit.record("STUDENTS_IMPORTED", "section", sectionId, null,
					Map.of("count", admitted.size(), "admissionDate", admissionDate.toString()));
			return new ImportResult(check.section(), admitted.size(), admitted);
		});
	}

	private Checked checked(InputStream file, long sectionId, LocalDate admissionDate) {
		var year = yearContext.requireWritable();
		var section = structure.requireSection(sectionId);
		if (!section.active()) {
			throw ApiException.businessRule("SECTION_INACTIVE", section.label() + " is not active");
		}
		if (admissionDate == null) {
			throw ApiException.businessRule("FIELD_REQUIRED", "Choose the admission date");
		}
		if (admissionDate.isAfter(year.endDate())) {
			throw ApiException.businessRule("DATE_OUTSIDE_YEAR", "The admission date is after the end of " + year.name());
		}
		List<String> fileProblems = new ArrayList<>();
		List<Map<String, String>> raw = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		read(file, fileProblems, raw, rowNumbers);

		LocalDate today = access.today();
		List<ImportRow> rows = new ArrayList<>();
		List<Planned> planned = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		Map<String, String> newFamilies = new HashMap<>();
		for (int i = 0; i < raw.size(); i++) {
			Map<String, String> r = raw.get(i);
			int rowNo = rowNumbers.get(i);
			List<String> problems = new ArrayList<>();
			for (Column c : COLUMNS) {
				if (c.required() && blank(r.get(c.key()))) {
					problems.add(c.heading() + " is missing");
				}
			}
			String name = clean(r.get("name"));
			String gender = gender(r.get("gender"), problems);
			LocalDate dob = date(r.get("dob"), "Date of birth", problems);
			if (dob != null && dob.isAfter(today)) {
				problems.add("Date of birth is in the future");
			}
			else if (dob != null && dob.isAfter(admissionDate)) {
				problems.add("Date of birth is after the admission date");
			}
			String fatherPhone = format(() -> PakFormats.phone(phoneText(r.get("fatherPhone")), "Father's mobile"), problems);
			String motherPhone = format(() -> PakFormats.phone(phoneText(r.get("motherPhone")), "Mother's mobile"), problems);
			String fatherCnic = format(() -> PakFormats.cnic(r.get("fatherCnic"), "Father's CNIC"), problems);
			String bForm = format(() -> PakFormats.cnic(r.get("bForm"), "B-Form no."), problems);
			String fatherName = clean(r.get("fatherName"));

			// The same child twice in the sheet, or already in the school
			String family = null;
			Long existingFamilyId = null;
			if (name != null && fatherPhone != null) {
				String key = name.toLowerCase(Locale.ROOT) + "|" + fatherPhone;
				Integer earlier = seen.putIfAbsent(key, rowNo);
				if (earlier != null) {
					problems.add("Same student as row " + earlier);
				}
				String already = jdbc.query("""
						select s.gr_no from student s join family f on f.id = s.family_id
						where lower(s.name) = lower(?) and (f.father_phone = ? or f.mother_phone = ?) and s.status = 'ACTIVE'
						limit 1""", rs -> rs.next() ? rs.getString(1) : null, name, fatherPhone, fatherPhone);
				if (already != null) {
					problems.add("Already admitted (GR no. " + already + ")");
				}
				var match = families.findByContact(fatherPhone, null).stream()
					.filter(f -> fatherPhone.equals(f.fatherPhone()))
					.findFirst();
				if (match.isPresent()) {
					existingFamilyId = match.get().id();
					family = "Joins family " + match.get().familyNo() + " (" + match.get().fatherName() + ")";
				}
				else {
					String first = newFamilies.putIfAbsent(fatherPhone, name);
					family = first == null ? "New family" : "Same new family as " + first;
				}
			}
			rows.add(new ImportRow(rowNo, name, gender, dob, fatherName, fatherPhone, family, List.copyOf(problems)));
			if (problems.isEmpty()) {
				StudentDetails student = new StudentDetails(name, gender, dob, clean(r.get("placeOfBirth")), bForm,
						clean(r.get("religion")), "Pakistani", null, null, clean(r.get("previousSchool")), null, null, null);
				FamilyDetails parents = new FamilyDetails(fatherName, fatherCnic, fatherPhone, null, clean(r.get("motherName")),
						null, motherPhone, null, clean(r.get("address")), null, null, "SMS", null);
				planned.add(new Planned(rows.getLast(), student, parents, existingFamilyId));
			}
		}
		if (raw.isEmpty() && fileProblems.isEmpty()) {
			fileProblems.add("The sheet has no students: fill in one student per row, starting from row 2");
		}
		int bad = (int) rows.stream().filter(r -> !r.problems().isEmpty()).count();
		boolean ready = fileProblems.isEmpty() && bad == 0;
		return new Checked(new ImportCheck(section.label(), admissionDate, rows.size(), bad, fileProblems, rows, ready),
				ready ? planned : List.of());
	}

	// ---------------------------------------------------------------- reading the sheet

	private static void read(InputStream file, List<String> fileProblems, List<Map<String, String>> rows,
			List<Integer> rowNumbers) {
		Workbook book;
		try {
			book = WorkbookFactory.create(file);
		}
		catch (IOException | RuntimeException e) {
			fileProblems.add("This is not an Excel file. Download the template, fill it in and upload it (.xlsx)");
			return;
		}
		try (book) {
			Sheet sheet = book.getSheet("Students") != null ? book.getSheet("Students") : book.getSheetAt(0);
			Row heading = sheet.getRow(sheet.getFirstRowNum());
			if (heading == null) {
				fileProblems.add("The sheet is empty: use the template");
				return;
			}
			DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
			Map<String, Integer> index = new LinkedHashMap<>();
			for (Cell cell : heading) {
				String key = keyOf(formatter.formatCellValue(cell));
				if (key != null) {
					index.putIfAbsent(key, cell.getColumnIndex());
				}
			}
			for (Column c : COLUMNS) {
				if (c.required() && !index.containsKey(c.key())) {
					fileProblems.add("The column \"" + c.heading() + "\" is missing: use the template");
				}
			}
			if (!fileProblems.isEmpty()) {
				return;
			}
			for (int r = heading.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
				Row row = sheet.getRow(r);
				if (row == null) {
					continue;
				}
				Map<String, String> values = new HashMap<>();
				boolean any = false;
				for (var e : index.entrySet()) {
					String v = value(row.getCell(e.getValue()), formatter);
					values.put(e.getKey(), v);
					any |= v != null;
				}
				if (!any) {
					continue; // empty row
				}
				if (rows.size() == MAX_ROWS) {
					fileProblems.add("Import at most " + MAX_ROWS + " students at a time");
					return;
				}
				rows.add(values);
				rowNumbers.add(r + 1);
			}
		}
		catch (IOException e) {
			fileProblems.add("The Excel file could not be read");
		}
	}

	/** The column for a heading: "Father's mobile *" → fatherPhone. */
	private static String keyOf(String heading) {
		String h = normal(heading);
		for (Column c : COLUMNS) {
			if (normal(c.heading()).equals(h)) {
				return c.key();
			}
		}
		return switch (h) {
			case "name", "studentsname", "fullname" -> "name";
			case "dateofbirthddmmyyyy", "dob", "birthdate" -> "dob";
			case "fathersphone", "fathermobile", "fatherphone", "mobile", "phone" -> "fatherPhone";
			case "fathername" -> "fatherName";
			case "bformnumber", "bform" -> "bForm";
			case "address" -> "address";
			default -> null;
		};
	}

	private static String normal(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/** Cell text; a date cell as yyyy-mm-dd, a number without ".0" or exponent. */
	private static String value(Cell cell, DataFormatter formatter) {
		if (cell == null) {
			return null;
		}
		String text;
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		if (type == CellType.NUMERIC) {
			text = DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
					: BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
		}
		else {
			text = formatter.formatCellValue(cell);
		}
		return blank(text) ? null : text.trim();
	}

	// ---------------------------------------------------------------- values

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String clean(String s) {
		return blank(s) ? null : s.trim().replaceAll("\\s+", " ");
	}

	private static String gender(String raw, List<String> problems) {
		if (blank(raw)) {
			return null;
		}
		return switch (normal(raw)) {
			case "male", "m", "boy", "b" -> "MALE";
			case "female", "f", "girl", "g" -> "FEMALE";
			default -> {
				problems.add("Gender must be Male or Female (not \"" + raw.trim() + "\")");
				yield null;
			}
		};
	}

	/** Strict: 31-02-2016 is refused, never quietly turned into 29 February. */
	private static final List<DateTimeFormatter> DATES = java.util.stream.Stream.of("uuuu-M-d", "d-M-uuuu", "d/M/uuuu",
			"d.M.uuuu", "d MMM uuuu", "d-MMM-uuuu")
		.map(p -> DateTimeFormatter.ofPattern(p, Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT))
		.toList();

	private static LocalDate date(String raw, String field, List<String> problems) {
		if (blank(raw)) {
			return null;
		}
		for (DateTimeFormatter f : DATES) {
			try {
				return LocalDate.parse(raw.trim(), f);
			}
			catch (DateTimeParseException e) {
				// try the next format
			}
		}
		problems.add(field + " \"" + raw.trim() + "\" is not a date: write it like 14-03-2016");
		return null;
	}

	/** A mobile typed into Excel as a number lost its leading 0: 3001234567 → 03001234567. */
	private static String phoneText(String raw) {
		if (raw != null && raw.matches("3\\d{9}")) {
			return "0" + raw;
		}
		return raw;
	}

	private static String format(java.util.function.Supplier<String> parse, List<String> problems) {
		try {
			return parse.get();
		}
		catch (ApiException e) {
			problems.add(e.getMessage());
			return null;
		}
	}
}
