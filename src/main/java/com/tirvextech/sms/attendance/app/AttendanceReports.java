package com.selectcode.sms.attendance.app;

import com.selectcode.sms.settings.SettingKey;
import com.selectcode.sms.settings.SettingsService;
import com.selectcode.sms.shared.attendance.StudentAbsences;
import com.selectcode.sms.shared.money.MoneyInput;
import com.selectcode.sms.shared.pdf.Letterhead;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.pdf.PdfKit;
import com.selectcode.sms.shared.year.AcademicYearLookup.YearRef;
import com.selectcode.sms.shared.year.WorkingDayCalendar;
import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance figures (FR 13.4–13.7). Percentage (DECISIONS D-09) = (present + late + ½ × half days) ÷ (marked working
 * days − approved leave) × 100, rounded to 1 dp HALF_UP. Only working days count; registers not taken are left out
 * (they appear in the missing-register list instead). A student is counted only for the days they were in the section.
 */
@Service
public class AttendanceReports implements StudentAbsences, com.selectcode.sms.shared.attendance.AttendancePercent,
		com.selectcode.sms.shared.attendance.RepeatedAbsenceCount {

	public record StudentFigures(long studentId, String grNo, String name, String sectionLabel, int markedDays,
			int present, int late, int halfDays, int absent, int leave, String percent) {
	}

	public record RepeatedAbsence(long studentId, String grNo, String name, String sectionLabel, int daysInARow,
			LocalDate since, String fatherPhone) {
	}

	public record SectionSummary(long sectionId, String sectionLabel, int students, int registersTaken,
			int workingDays, int absences, String averagePercent) {
	}

	public record StudentMonth(String month, int workingDays, int present, int late, int halfDays, int absent,
			int leave, String percent) {
	}

	private static final Font TINY = new Font(Font.HELVETICA, 6.5f);

	private static final Font TINY_BOLD = new Font(Font.HELVETICA, 6.5f, Font.BOLD);

	private final JdbcTemplate jdbc;

	private final WorkingDayCalendar calendar;

	private final AttendanceAccess access;

	private final SettingsService settings;

	private final LetterheadSource letterhead;

	private final Clock clock;

	AttendanceReports(JdbcTemplate jdbc, WorkingDayCalendar calendar, AttendanceAccess access, SettingsService settings,
			LetterheadSource letterhead, Clock clock) {
		this.jdbc = jdbc;
		this.calendar = calendar;
		this.access = access;
		this.settings = settings;
		this.letterhead = letterhead;
		this.clock = clock;
	}

	/** D-09 percentage, or null when nothing countable was marked. */
	public static String percent(int present, int late, int halfDays, int markedDays, int leave) {
		return com.selectcode.sms.shared.attendance.AttendancePercent.of(present, late, halfDays, markedDays, leave);
	}

	private Array dates(List<LocalDate> days) {
		return jdbc.execute((java.sql.Connection c) -> c.createArrayOf("date",
				days.stream().map(Date::valueOf).toArray()));
	}

	private LocalDate clampTo(LocalDate to) {
		LocalDate today = MoneyInput.today(clock);
		return to.isAfter(today) ? today : to;
	}

	/** Figures per student for a section (or the whole school when null) over a period. */
	@Transactional(readOnly = true)
	public List<StudentFigures> figures(Long sectionId, LocalDate from, LocalDate to) {
		LocalDate end = clampTo(to);
		if (end.isBefore(from)) {
			return List.of();
		}
		YearRef year = access.yearOf(end);
		Optional<Set<Long>> allowed = access.viewable(year, end);
		if (sectionId != null) {
			access.checkView(year, end, sectionId);
		}
		Array days = dates(calendar.workingDays(from, end));
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(end), Date.valueOf(end), days));
		String sectionFilter = "";
		if (sectionId != null) {
			sectionFilter = " and cur.section_id = ?";
			args.add(sectionId);
		}
		return jdbc.query("""
				select s.id, s.gr_no, s.name, c.name || ' - ' || sec.name, cur.section_id,
				       count(a.id), count(a.id) filter (where a.status = 'P'), count(a.id) filter (where a.status = 'L'),
				       count(a.id) filter (where a.status = 'HD'), count(a.id) filter (where a.status = 'A'),
				       count(a.id) filter (where a.status = 'LV')
				from student s
				join lateral (select e.section_id from enrolment e where e.student_id = s.id and e.effective_from <= ?
				                and (e.effective_to is null or e.effective_to >= ?) order by e.effective_from desc limit 1) cur on true
				join section sec on sec.id = cur.section_id join school_class c on c.id = sec.class_id
				left join student_attendance a on a.student_id = s.id and a.att_date = any(?)
				where 1 = 1""" + sectionFilter + " " + """
				group by s.id, s.gr_no, s.name, c.name, sec.name, cur.section_id, c.display_order
				order by c.display_order, sec.name, s.name""",
				(rs, i) -> new Object[] { new StudentFigures(rs.getLong(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
						rs.getInt(11), percent(rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(6), rs.getInt(11))),
						rs.getLong(5) },
				args.toArray()).stream()
			.filter(r -> allowed.isEmpty() || allowed.get().contains((Long) r[1]))
			.map(r -> (StudentFigures) r[0])
			.toList();
	}

	/** Below the school's low-attendance threshold (FR 13.6). */
	@Transactional(readOnly = true)
	public List<StudentFigures> low(Long sectionId, LocalDate from, LocalDate to) {
		BigDecimal threshold = BigDecimal.valueOf(settings.integer(SettingKey.LOW_ATTENDANCE_PERCENT));
		return figures(sectionId, from, to).stream()
			.filter(f -> f.percent() != null && new BigDecimal(f.percent()).compareTo(threshold) < 0)
			.toList();
	}

	/** Present every marked working day (no absence, late, half day or leave) (FR 13.7). */
	@Transactional(readOnly = true)
	public List<StudentFigures> fullAttendance(Long sectionId, LocalDate from, LocalDate to) {
		return figures(sectionId, from, to).stream()
			.filter(f -> f.markedDays() > 0 && f.present() == f.markedDays())
			.toList();
	}

	/**
	 * Students absent on the latest working days in a row, at least the school's alert number (QA-ATT-007). Days off
	 * do not break the run; a working day without a record does.
	 */
	@Override
	@Transactional(readOnly = true)
	public int repeatedAbsenceCount(LocalDate asOf) {
		return repeatedAbsences(asOf).size();
	}

	@Override
	@Transactional(readOnly = true)
	public List<RepeatedStudent> repeatedStudents(LocalDate asOf) {
		return repeatedAbsences(asOf).stream().map(r -> new RepeatedStudent(r.studentId(), r.grNo(), r.name(),
				jdbc.queryForObject("select section_id from enrolment where student_id = ? order by effective_from desc limit 1",
						Long.class, r.studentId()), r.sectionLabel(), r.daysInARow(), r.since())).toList();
	}

	@Transactional(readOnly = true)
	public List<RepeatedAbsence> repeatedAbsences(LocalDate asOf) {
		int threshold = Math.max(2, settings.integer(SettingKey.REPEATED_ABSENCE_DAYS));
		LocalDate end = clampTo(asOf);
		List<LocalDate> days = calendar.workingDays(end.minusDays(60), end);
		if (days.isEmpty()) {
			return List.of();
		}
		YearRef year = access.yearOf(end);
		Optional<Set<Long>> allowed = access.viewable(year, end);
		Map<Long, Map<LocalDate, String>> byStudent = new HashMap<>();
		jdbc.query("select student_id, att_date, status from student_attendance where att_date = any(?)", rs -> {
			byStudent.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getDate(2).toLocalDate(), rs.getString(3));
		}, dates(days));
		List<RepeatedAbsence> result = new ArrayList<>();
		for (var entry : byStudent.entrySet()) {
			int run = 0;
			LocalDate since = null;
			for (int i = days.size() - 1; i >= 0; i--) {
				String status = entry.getValue().get(days.get(i));
				if (status == null && run == 0) {
					continue; // register not taken yet today: look at the previous working day
				}
				if (!"A".equals(status)) {
					break;
				}
				run++;
				since = days.get(i);
			}
			if (run >= threshold) {
				var s = jdbc.queryForMap("""
						select st.gr_no, st.name, f.father_phone,
						       (select c.name || ' - ' || sec.name from enrolment e join section sec on sec.id = e.section_id
						        join school_class c on c.id = sec.class_id where e.student_id = st.id
						        order by e.effective_from desc limit 1) as section,
						       (select e.section_id from enrolment e where e.student_id = st.id
						        order by e.effective_from desc limit 1) as section_id
						from student st join family f on f.id = st.family_id where st.id = ?""", entry.getKey());
				if (allowed.isPresent() && !allowed.get().contains(((Number) s.get("section_id")).longValue())) {
					continue;
				}
				result.add(new RepeatedAbsence(entry.getKey(), (String) s.get("gr_no"), (String) s.get("name"),
						(String) s.get("section"), run, since, (String) s.get("father_phone")));
			}
		}
		result.sort((a, b) -> Integer.compare(b.daysInARow(), a.daysInARow()));
		return result;
	}

	/** Per section: registers taken, absences and average percentage over a period (FR 13.7 class summaries). */
	@Transactional(readOnly = true)
	public List<SectionSummary> sectionSummaries(LocalDate from, LocalDate to) {
		LocalDate end = clampTo(to);
		List<LocalDate> days = end.isBefore(from) ? List.of() : calendar.workingDays(from, end);
		Map<Long, List<StudentFigures>> bySection = new LinkedHashMap<>();
		Map<Long, String> labels = new HashMap<>();
		for (StudentFigures f : figures(null, from, end)) {
			Long id = jdbc.queryForObject("""
					select section_id from enrolment where student_id = ? and effective_from <= ?
					  and (effective_to is null or effective_to >= ?) order by effective_from desc limit 1""", Long.class,
					f.studentId(), Date.valueOf(end), Date.valueOf(end));
			bySection.computeIfAbsent(id, k -> new ArrayList<>()).add(f);
			labels.put(id, f.sectionLabel());
		}
		List<SectionSummary> rows = new ArrayList<>();
		Array dayArray = dates(days);
		for (var e : bySection.entrySet()) {
			Integer taken = jdbc.queryForObject(
					"select count(*) from register_completion where section_id = ? and att_date = any(?)", Integer.class,
					e.getKey(), dayArray);
			List<BigDecimal> percents = e.getValue().stream().filter(f -> f.percent() != null)
				.map(f -> new BigDecimal(f.percent())).toList();
			String average = percents.isEmpty() ? null
					: percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
						.divide(BigDecimal.valueOf(percents.size()), 1, RoundingMode.HALF_UP).toPlainString();
			rows.add(new SectionSummary(e.getKey(), labels.get(e.getKey()), e.getValue().size(), taken == null ? 0 : taken,
					days.size(), e.getValue().stream().mapToInt(StudentFigures::absent).sum(), average));
		}
		return rows;
	}

	/** Month by month for one student, for the profile (FR 5.1, 13.5). */
	@Transactional(readOnly = true)
	public List<StudentMonth> studentMonths(long studentId, LocalDate from, LocalDate to) {
		LocalDate end = clampTo(to);
		List<StudentMonth> rows = new ArrayList<>();
		for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
			LocalDate a = m.atDay(1).isBefore(from) ? from : m.atDay(1);
			LocalDate b = m.atEndOfMonth().isAfter(end) ? end : m.atEndOfMonth();
			List<LocalDate> days = calendar.workingDays(a, b);
			Map<String, Integer> c = new HashMap<>();
			jdbc.query("select status from student_attendance where student_id = ? and att_date = any(?)",
					rs -> { c.merge(rs.getString(1), 1, Integer::sum); }, studentId, dates(days));
			int marked = c.values().stream().mapToInt(Integer::intValue).sum();
			rows.add(new StudentMonth(m.toString(), days.size(), c.getOrDefault("P", 0), c.getOrDefault("L", 0),
					c.getOrDefault("HD", 0), c.getOrDefault("A", 0), c.getOrDefault("LV", 0),
					percent(c.getOrDefault("P", 0), c.getOrDefault("L", 0), c.getOrDefault("HD", 0), marked,
							c.getOrDefault("LV", 0))));
		}
		return rows;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, Integer> absencesInMonth(YearMonth month) {
		List<LocalDate> days = calendar.workingDays(month.atDay(1), clampTo(month.atEndOfMonth()));
		Map<Long, Integer> result = new HashMap<>();
		if (days.isEmpty()) {
			return result;
		}
		jdbc.query("""
				select student_id, count(*) from student_attendance where status = 'A' and att_date = any(?)
				group by student_id""", rs -> {
			result.put(rs.getLong(1), rs.getInt(2));
		}, dates(days));
		return result;
	}

	// ------------------------------------------------------- monthly register

	/**
	 * The monthly register (FR 13.4): one row per student who was in the section during the month, one column per day.
	 * A student moved in or out mid-month is shown only for their own dates (QA-ATT-011).
	 */
	@Transactional(readOnly = true)
	public byte[] monthlyRegister(long sectionId, YearMonth month) {
		LocalDate first = month.atDay(1);
		LocalDate last = month.atEndOfMonth();
		LocalDate checkOn = last.isAfter(MoneyInput.today(clock)) ? MoneyInput.today(clock) : last;
		access.checkView(access.yearOf(checkOn), checkOn, sectionId);
		String label = access.sectionLabel(sectionId);
		List<LocalDate> working = calendar.workingDays(first, last);
		record Row(long id, String grNo, String name, LocalDate from, LocalDate to) {
		}
		List<Row> students = jdbc.query("""
				select s.id, s.gr_no, s.name, greatest(e.effective_from, ?), least(coalesce(e.effective_to, ?), ?)
				from enrolment e join student s on s.id = e.student_id
				where e.section_id = ? and e.effective_from <= ? and (e.effective_to is null or e.effective_to >= ?)
				order by s.name""", (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
				rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate()), Date.valueOf(first), Date.valueOf(last),
				Date.valueOf(last), sectionId, Date.valueOf(last), Date.valueOf(first));
		Map<Long, Map<LocalDate, String>> marks = new HashMap<>();
		jdbc.query("""
				select student_id, att_date, status from student_attendance
				where section_id = ? and att_date between ? and ?""", rs -> {
			marks.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getDate(2).toLocalDate(), rs.getString(3));
		}, sectionId, Date.valueOf(first), Date.valueOf(last));
		Letterhead lh = letterhead.current();
		int daysInMonth = month.lengthOfMonth();
		return PdfKit.render(PageSize.A4.rotate(), 20, doc -> {
			PdfKit.letterhead(doc, lh);
			PdfKit.title(doc, "ATTENDANCE REGISTER - " + label + " - "
					+ month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)));
			float[] widths = new float[2 + daysInMonth + 6];
			java.util.Arrays.fill(widths, 1f);
			widths[0] = 2.2f;
			widths[1] = 6f;
			PdfPTable t = new PdfPTable(widths);
			t.setWidthPercentage(100);
			cell(t, "GR", TINY_BOLD, null);
			cell(t, "Name", TINY_BOLD, null);
			for (int d = 1; d <= daysInMonth; d++) {
				cell(t, String.valueOf(d), TINY_BOLD, working.contains(month.atDay(d)) ? null : new Color(225, 225, 225));
			}
			for (String h : List.of("P", "L", "HD", "A", "LV", "%")) {
				cell(t, h, TINY_BOLD, new Color(243, 244, 246));
			}
			for (Row r : students) {
				cell(t, r.grNo(), TINY, null);
				cell(t, r.name(), TINY, null);
				Map<String, Integer> c = new HashMap<>();
				int marked = 0;
				for (int d = 1; d <= daysInMonth; d++) {
					LocalDate day = month.atDay(d);
					boolean inSection = !day.isBefore(r.from()) && !day.isAfter(r.to());
					boolean open = working.contains(day);
					String code = inSection && open ? marks.getOrDefault(r.id(), Map.of()).get(day) : null;
					if (code != null) {
						c.merge(code, 1, Integer::sum);
						marked++;
					}
					cell(t, !inSection ? "" : !open ? "-" : code == null ? "" : code, TINY,
							open ? ("A".equals(code) ? new Color(254, 226, 226) : null) : new Color(225, 225, 225));
				}
				for (String h : List.of("P", "L", "HD", "A", "LV")) {
					cell(t, String.valueOf(c.getOrDefault(h, 0)), TINY, null);
				}
				String pct = percent(c.getOrDefault("P", 0), c.getOrDefault("L", 0), c.getOrDefault("HD", 0), marked,
						c.getOrDefault("LV", 0));
				cell(t, pct == null ? "" : pct, TINY, null);
			}
			PdfKit.add(doc, t);
			PdfKit.paragraph(doc, "P present, L late, HD half day, A absent, LV approved leave, - school closed. "
					+ "Percentage = (P + L + ½ HD) ÷ (marked days − LV). Working days this month: " + working.size() + ".");
			PdfKit.signatures(doc, lh, List.of("Class teacher", "Principal"), false);
		});
	}

	private static void cell(PdfPTable t, String text, Font font, Color background) {
		PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
		c.setPadding(1.5f);
		c.setHorizontalAlignment(Element.ALIGN_CENTER);
		if (background != null) {
			c.setBackgroundColor(background);
		}
		t.addCell(c);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> percent(long studentId, LocalDate from, LocalDate to) {
		LocalDate end = clampTo(to);
		if (end.isBefore(from)) {
			return Optional.empty();
		}
		Map<String, Integer> c = new HashMap<>();
		jdbc.query("select status from student_attendance where student_id = ? and att_date = any(?)",
				rs -> { c.merge(rs.getString(1), 1, Integer::sum); }, studentId, dates(calendar.workingDays(from, end)));
		int marked = c.values().stream().mapToInt(Integer::intValue).sum();
		return Optional.ofNullable(percent(c.getOrDefault("P", 0), c.getOrDefault("L", 0), c.getOrDefault("HD", 0),
				marked, c.getOrDefault("LV", 0)));
	}
}
