package com.tirvextech.sms.academics.app;

import com.tirvextech.sms.shared.web.ApiException;
import com.tirvextech.sms.shared.year.AcademicYearLookup;
import com.tirvextech.sms.shared.year.YearContext;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The automatic timetable (FR 6.4). From each class's periods a week per subject, the subject teachers of each
 * section, the working week and the bell schedule, it proposes a week for every section in which no teacher is in two
 * places at once. Locked periods, and the timetables of sections outside the run, are kept as they are. Nothing is
 * saved: the user previews the proposal and saves it ({@link TeachingService#saveTimetables}).
 *
 * <p>Search: lessons are placed hardest first (busiest teachers, double periods), each in the free slot that best
 * spreads the subject across the week, with chronological backtracking within a step budget and a few restarts with
 * different tie-breaks. If not everything fits, the fullest attempt is returned with the reasons.
 */
@Service
public class TimetableGenerator {

	public record Proposal(boolean complete, int placed, int total, List<String> problems, List<SectionPlan> sections) {
	}

	public record SectionPlan(long sectionId, String sectionLabel, List<TeachingService.PeriodView> periods) {
	}

	private static final int STEP_BUDGET = 250_000;

	private static final int ATTEMPTS = 8;

	private static final long TIME_LIMIT_MS = 8_000;

	private final JdbcTemplate jdbc;

	private final AcademicYearLookup years;

	private final YearContext yearContext;

	private final BellSchedule bells;

	TimetableGenerator(JdbcTemplate jdbc, AcademicYearLookup years, YearContext yearContext, BellSchedule bells) {
		this.jdbc = jdbc;
		this.years = years;
		this.yearContext = yearContext;
		this.bells = bells;
	}

	// ------------------------------------------------------------------ model

	private record Section(int index, long id, long classId, String label, Long classTeacher) {
	}

	private record Teacher(int index, long id, String name) {
	}

	/** A lesson to place: one period, or two back-to-back for a double period. */
	private record Unit(int section, long subjectId, String subjectName, int subjectIndex, Teacher teacher, int size) {
	}

	private record Placement(int day, int start) {
	}

	/** A slot of the week: the day and the indexes (0-based) of the teaching periods held that day. */
	private record Day(int weekday, int[] periods) {
	}

	/** The state of the week while lessons are placed. */
	private static final class Grid {

		final boolean[][][] sectionBusy; // [section][day][period]

		final boolean[][][] teacherBusy; // [teacher][day][period]

		final int[][][] subjectOnDay; // [section][subjectIndex][day]

		final int[][] teacherOnDay; // [teacher][day]

		Grid(int sections, int teachers, int subjects, int days, int periods) {
			sectionBusy = new boolean[sections][days][periods];
			teacherBusy = new boolean[teachers][days][periods];
			subjectOnDay = new int[sections][subjects][days];
			teacherOnDay = new int[teachers][days];
		}
	}

	// --------------------------------------------------------------- generate

	@Transactional(readOnly = true)
	public Proposal generate(long yearId, List<Long> sectionIds) {
		var year = years.findById(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
		yearContext.requireWritable(year);
		List<String> problems = new ArrayList<>();

		// Bell schedule → teaching periods, which may run back to back, which happen on half days
		List<BellSchedule.Slot> slots = bells.slots(yearId);
		List<BellSchedule.Slot> periods = slots.stream().filter(BellSchedule.Slot::period).toList();
		if (periods.isEmpty()) {
			throw ApiException.businessRule("BELL_SCHEDULE_REQUIRED",
					"Set the bell schedule first: the periods of the school day and their times.");
		}
		int periodCount = periods.size();
		boolean[] pairsWithNext = new boolean[periodCount];
		for (int i = 0, p = -1; i < slots.size(); i++) {
			if (slots.get(i).period()) {
				p++;
				pairsWithNext[p] = i + 1 < slots.size() && slots.get(i + 1).period();
			}
		}

		// Working week
		Map<Integer, String> week = new LinkedHashMap<>();
		jdbc.query("select weekday, day_type from working_day where academic_year_id = ? order by weekday",
				rs -> {
					week.put(rs.getInt(1), rs.getString(2));
				}, yearId);
		if (week.isEmpty()) {
			for (int d = 1; d <= 6; d++) {
				week.put(d, "FULL");
			}
		}
		List<Day> days = new ArrayList<>();
		for (var e : week.entrySet()) {
			if ("OFF".equals(e.getValue())) {
				continue;
			}
			boolean half = "HALF".equals(e.getValue());
			int[] held = java.util.stream.IntStream.range(0, periodCount)
				.filter(p -> !half || periods.get(p).onHalfDay())
				.toArray();
			days.add(new Day(e.getKey(), held));
		}
		if (days.isEmpty()) {
			throw ApiException.businessRule("NO_WORKING_DAYS", "Set the school's working days first");
		}

		// Sections in the run
		List<Section> sections = new ArrayList<>();
		Map<Long, Section> sectionById = new HashMap<>();
		Set<Long> wanted = sectionIds == null || sectionIds.isEmpty() ? null : new HashSet<>(sectionIds);
		jdbc.query("""
				select s.id, s.class_id, c.name || ' - ' || s.name,
				       (select a.staff_id from teaching_assignment a join staff st on st.id = a.staff_id
				        where a.academic_year_id = ? and a.section_id = s.id and a.subject_id is null
				          and a.effective_to is null and st.status = 'ACTIVE' limit 1)
				from section s join school_class c on c.id = s.class_id
				where s.status = 'ACTIVE' and c.status = 'ACTIVE'
				order by c.display_order, s.name""", rs -> {
			long id = rs.getLong(1);
			if (wanted == null || wanted.contains(id)) {
				Section s = new Section(sections.size(), id, rs.getLong(2), rs.getString(3), (Long) rs.getObject(4));
				sections.add(s);
				sectionById.put(id, s);
			}
		}, yearId);
		if (sections.isEmpty()) {
			throw ApiException.businessRule("NO_SECTIONS", "There are no sections to make a timetable for");
		}

		// Subjects a week per class
		record ClassSubject(long subjectId, String name, int perWeek, boolean doublePeriod) {
		}
		Map<Long, List<ClassSubject>> subjectsByClass = new HashMap<>();
		Set<Long> classesWithoutCounts = new HashSet<>();
		jdbc.query("""
				select cs.class_id, cs.subject_id, sub.name, cs.periods_per_week, cs.double_period
				from class_subject cs join subject sub on sub.id = cs.subject_id
				where cs.academic_year_id = ? and cs.status = 'ACTIVE' order by cs.class_id, cs.display_order""", rs -> {
			long classId = rs.getLong(1);
			int perWeek = rs.getInt(4);
			if (rs.wasNull() || perWeek == 0) {
				classesWithoutCounts.add(classId);
				return;
			}
			subjectsByClass.computeIfAbsent(classId, k -> new ArrayList<>())
				.add(new ClassSubject(rs.getLong(2), rs.getString(3), perWeek, rs.getBoolean(5)));
		}, yearId);
		Map<Long, Integer> subjectIndex = new HashMap<>();
		subjectsByClass.values().forEach(list -> list.forEach(cs -> subjectIndex.putIfAbsent(cs.subjectId(), subjectIndex.size())));

		// Subject teachers of each section (current, still employed)
		Map<String, Long> teacherOf = new HashMap<>();
		Map<Long, String> names = new HashMap<>();
		jdbc.query("""
				select a.section_id, a.subject_id, a.staff_id, st.name from teaching_assignment a
				join staff st on st.id = a.staff_id
				where a.academic_year_id = ? and a.effective_to is null and a.subject_id is not null
				  and st.status = 'ACTIVE'""", rs -> {
			teacherOf.put(rs.getLong(1) + ":" + rs.getLong(2), rs.getLong(3));
			names.put(rs.getLong(3), rs.getString(4));
		}, yearId);

		// Periods kept as they are: other sections' timetables, and locked periods of the sections in the run
		record Fixed(long sectionId, int weekday, int periodNo, Long subjectId, Long staffId, boolean locked) {
		}
		List<Fixed> fixed = jdbc.query("""
				select t.section_id, t.weekday, t.period_no, t.subject_id, t.staff_id, t.locked, st.name
				from timetable_period t left join staff st on st.id = t.staff_id
				where t.academic_year_id = ?""", (rs, i) -> {
			if (rs.getObject(5) != null) {
				names.put(rs.getLong(5), rs.getString(7));
			}
			return new Fixed(rs.getLong(1), rs.getInt(2), rs.getInt(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
					rs.getBoolean(6));
		}, yearId).stream().filter(f -> !sectionById.containsKey(f.sectionId()) || f.locked()).toList();

		Map<Long, Teacher> teachers = new HashMap<>();
		java.util.function.Function<Long, Teacher> teacher = id -> id == null ? null
				: teachers.computeIfAbsent(id, k -> new Teacher(teachers.size(), k, names.getOrDefault(k, "A teacher")));
		fixed.forEach(f -> teacher.apply(f.staffId()));

		// Lessons to place
		Map<Integer, Integer> dayIndex = new HashMap<>();
		for (int d = 0; d < days.size(); d++) {
			dayIndex.put(days.get(d).weekday(), d);
		}
		Map<String, Integer> lockedCount = new HashMap<>();
		for (Fixed f : fixed) {
			if (f.locked() && sectionById.containsKey(f.sectionId()) && f.subjectId() != null) {
				lockedCount.merge(f.sectionId() + ":" + f.subjectId(), 1, Integer::sum);
			}
		}
		List<Unit> units = new ArrayList<>();
		Set<String> noTeacher = new java.util.TreeSet<>();
		for (Section s : sections) {
			List<ClassSubject> list = subjectsByClass.getOrDefault(s.classId(), List.of());
			if (list.isEmpty()) {
				problems.add(classesWithoutCounts.contains(s.classId())
						? s.label() + ": set the periods a week of its subjects (School setup → Subjects & marks)"
						: s.label() + ": the class has no subjects this year (School setup → Subjects & marks)");
				continue;
			}
			for (ClassSubject cs : list) {
				int remaining = cs.perWeek() - lockedCount.getOrDefault(s.id() + ":" + cs.subjectId(), 0);
				if (remaining <= 0) {
					continue;
				}
				Long staffId = teacherOf.get(s.id() + ":" + cs.subjectId());
				if (staffId == null) {
					noTeacher.add(cs.name() + " in " + s.label());
				}
				Teacher t = teacher.apply(staffId);
				int idx = subjectIndex.get(cs.subjectId());
				int doubles = cs.doublePeriod() ? remaining / 2 : 0;
				for (int i = 0; i < doubles; i++) {
					units.add(new Unit(s.index(), cs.subjectId(), cs.name(), idx, t, 2));
				}
				for (int i = 0; i < remaining - doubles * 2; i++) {
					units.add(new Unit(s.index(), cs.subjectId(), cs.name(), idx, t, 1));
				}
			}
		}
		if (!noTeacher.isEmpty()) {
			problems.add("No teacher assigned for " + summarise(noTeacher)
					+ ": those periods are placed without a teacher (assign one under Teachers by section).");
		}

		Grid base = new Grid(sections.size(), Math.max(1, teachers.size()), Math.max(1, subjectIndex.size()), days.size(),
				periodCount);
		for (Fixed f : fixed) {
			Integer d = dayIndex.get(f.weekday());
			int p = f.periodNo() - 1;
			if (d == null || p < 0 || p >= periodCount) {
				continue;
			}
			Section s = sectionById.get(f.sectionId());
			if (s != null) {
				base.sectionBusy[s.index()][d][p] = true;
				if (f.subjectId() != null && subjectIndex.containsKey(f.subjectId())) {
					base.subjectOnDay[s.index()][subjectIndex.get(f.subjectId())][d]++;
				}
			}
			if (f.staffId() != null) {
				Teacher t = teachers.get(f.staffId());
				base.teacherBusy[t.index()][d][p] = true;
				base.teacherOnDay[t.index()][d]++;
			}
		}

		// Can it fit at all? (Reported, and the search still places as much as it can.)
		int slotsPerWeek = days.stream().mapToInt(d -> d.periods().length).sum();
		int[] sectionDemand = new int[sections.size()];
		Map<Teacher, Integer> teacherDemand = new HashMap<>();
		for (Unit u : units) {
			sectionDemand[u.section()] += u.size();
			if (u.teacher() != null) {
				teacherDemand.merge(u.teacher(), u.size(), Integer::sum);
			}
		}
		for (Section s : sections) {
			int free = slotsPerWeek - count(base.sectionBusy[s.index()]);
			if (sectionDemand[s.index()] > free) {
				problems.add(s.label() + " needs " + sectionDemand[s.index()] + " periods a week but has only " + free
						+ " free. Reduce its periods a week or add periods to the bell schedule.");
			}
		}
		for (var e : teacherDemand.entrySet()) {
			int free = slotsPerWeek - count(base.teacherBusy[e.getKey().index()]);
			if (e.getValue() > free) {
				problems.add(e.getKey().name() + " has " + e.getValue() + " periods a week but only " + free
						+ " free periods. Give some of their classes to another teacher.");
			}
		}

		// Search
		Search best = null;
		long deadline = System.currentTimeMillis() + TIME_LIMIT_MS;
		for (int attempt = 0; attempt < ATTEMPTS && System.currentTimeMillis() < deadline; attempt++) {
			Search s = new Search(units, sections, days, periodCount, pairsWithNext, copy(base), new Random(attempt * 7919L + 17),
					teacherDemand);
			s.run(deadline);
			if (best == null || s.placedPeriods() > best.placedPeriods()
					|| (s.placedPeriods() == best.placedPeriods() && s.penalty() < best.penalty())) {
				best = s;
			}
			if (best.complete() && attempt >= 1) {
				break;
			}
		}

		int total = units.stream().mapToInt(Unit::size).sum();
		int placed = best == null ? 0 : best.placedPeriods();
		if (best != null && !best.unplaced().isEmpty()) {
			Map<String, Integer> missing = new java.util.TreeMap<>();
			for (Unit u : best.unplaced()) {
				missing.merge(u.subjectName() + " in " + sections.get(u.section()).label()
						+ (u.teacher() == null ? "" : " (" + u.teacher().name() + ")"), u.size(), Integer::sum);
			}
			List<String> lines = missing.entrySet().stream().map(e -> e.getValue() + " × " + e.getKey()).toList();
			problems.add("Could not fit " + (total - placed) + " period(s): " + summarise(lines)
					+ ". Lock fewer periods, add periods to the day or share the load between teachers.");
		}

		// Proposal per section: locked periods + placed lessons, with bell times
		Map<Integer, List<TeachingService.PeriodView>> bySection = new HashMap<>();
		for (Fixed f : fixed) {
			Section s = sectionById.get(f.sectionId());
			if (s == null) {
				continue;
			}
			bySection.computeIfAbsent(s.index(), k -> new ArrayList<>()).add(view(s, f.weekday(), f.periodNo(), periods,
					f.subjectId(), f.subjectId() == null ? null : subjectName(f.subjectId()), f.staffId(),
					f.staffId() == null ? null : names.get(f.staffId()), true));
		}
		if (best != null) {
			for (int i = 0; i < best.size(); i++) {
				Placement pl = best.placementAt(i);
				if (pl == null) {
					continue;
				}
				Unit u = best.unit(i);
				Section s = sections.get(u.section());
				for (int k = 0; k < u.size(); k++) {
					bySection.computeIfAbsent(s.index(), x -> new ArrayList<>()).add(view(s, days.get(pl.day()).weekday(),
							pl.start() + k + 1, periods, u.subjectId(), u.subjectName(),
							u.teacher() == null ? null : u.teacher().id(), u.teacher() == null ? null : u.teacher().name(),
							false));
				}
			}
		}
		List<SectionPlan> plans = sections.stream()
			.map(s -> new SectionPlan(s.id(), s.label(), bySection.getOrDefault(s.index(), List.of())
				.stream()
				.sorted(Comparator.comparingInt(TeachingService.PeriodView::weekday)
					.thenComparingInt(TeachingService.PeriodView::periodNo))
				.toList()))
			.toList();
		return new Proposal(placed == total && problems.stream().noneMatch(p -> p.startsWith("Could not fit")), placed,
				total, problems, plans);
	}

	private String subjectName(long subjectId) {
		return jdbc.queryForList("select name from subject where id = ?", String.class, subjectId).stream().findFirst()
			.orElse(null);
	}

	private static TeachingService.PeriodView view(Section s, int weekday, int periodNo, List<BellSchedule.Slot> periods,
			Long subjectId, String subjectName, Long staffId, String staffName, boolean locked) {
		BellSchedule.Slot bell = periodNo - 1 < periods.size() ? periods.get(periodNo - 1) : null;
		LocalTime start = bell == null ? null : bell.startTime();
		LocalTime end = bell == null ? null : bell.endTime();
		return new TeachingService.PeriodView(s.id(), s.label(), weekday, periodNo, start, end, subjectId, subjectName,
				staffId, staffName, locked);
	}

	private static int count(boolean[][] grid) {
		int n = 0;
		for (boolean[] row : grid) {
			for (boolean b : row) {
				if (b) {
					n++;
				}
			}
		}
		return n;
	}

	private static String summarise(Collection<String> items) {
		List<String> list = new ArrayList<>(items);
		if (list.size() <= 6) {
			return String.join(", ", list);
		}
		return String.join(", ", list.subList(0, 6)) + " and " + (list.size() - 6) + " more";
	}

	private static Grid copy(Grid g) {
		Grid c = new Grid(g.sectionBusy.length, g.teacherBusy.length, g.subjectOnDay.length == 0 ? 1 : g.subjectOnDay[0].length,
				g.sectionBusy.length == 0 ? 1 : g.sectionBusy[0].length,
				g.sectionBusy.length == 0 || g.sectionBusy[0].length == 0 ? 1 : g.sectionBusy[0][0].length);
		deep(g.sectionBusy, c.sectionBusy);
		deep(g.teacherBusy, c.teacherBusy);
		for (int i = 0; i < g.subjectOnDay.length; i++) {
			for (int j = 0; j < g.subjectOnDay[i].length; j++) {
				c.subjectOnDay[i][j] = g.subjectOnDay[i][j].clone();
			}
		}
		for (int i = 0; i < g.teacherOnDay.length; i++) {
			c.teacherOnDay[i] = g.teacherOnDay[i].clone();
		}
		return c;
	}

	private static void deep(boolean[][][] from, boolean[][][] to) {
		for (int i = 0; i < from.length; i++) {
			for (int j = 0; j < from[i].length; j++) {
				to[i][j] = from[i][j].clone();
			}
		}
	}

	// ----------------------------------------------------------------- search

	/** One attempt: hardest lessons first, best slot first, backtracking within the step budget. */
	private static final class Search {

		private final List<Unit> units;

		private final List<Section> sections;

		private final List<Day> days;

		private final int periodCount;

		private final boolean[] pairsWithNext;

		private final Grid grid;

		private final Random random;

		private final Placement[] placement;

		private Placement[] best;

		private int bestPlaced = -1;

		private final List<Unit> unplaced = new ArrayList<>();

		private int penalty;

		Search(List<Unit> all, List<Section> sections, List<Day> days, int periodCount, boolean[] pairsWithNext, Grid grid,
				Random random, Map<Teacher, Integer> load) {
			this.sections = sections;
			this.days = days;
			this.periodCount = periodCount;
			this.pairsWithNext = pairsWithNext;
			this.grid = grid;
			this.random = random;
			// Hardest first: double periods, busiest teachers, then a random tie-break per attempt
			double[] jitter = random.doubles(all.size()).toArray();
			Integer[] order = new Integer[all.size()];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			Arrays.sort(order, Comparator.<Integer>comparingInt(i -> -all.get(i).size())
				.thenComparingInt(i -> -(all.get(i).teacher() == null ? 0 : load.getOrDefault(all.get(i).teacher(), 0)))
				.thenComparingDouble(i -> jitter[i]));
			this.units = Arrays.stream(order).map(all::get).toList();
			this.placement = new Placement[units.size()];
		}

		void run(long deadline) {
			int n = units.size();
			@SuppressWarnings("unchecked")
			List<Placement>[] candidates = new List[n];
			int[] next = new int[n];
			int i = 0;
			if (n > 0) {
				candidates[0] = candidates(units.get(0));
			}
			int steps = 0;
			while (i < n && i >= 0 && steps < STEP_BUDGET) {
				if ((++steps & 4095) == 0 && System.currentTimeMillis() > deadline) {
					break;
				}
				if (next[i] < candidates[i].size()) {
					Placement p = candidates[i].get(next[i]++);
					place(units.get(i), p);
					placement[i] = p;
					i++;
					if (i > bestPlaced) {
						bestPlaced = i;
						best = Arrays.copyOf(placement, n);
					}
					if (i < n) {
						candidates[i] = candidates(units.get(i));
						next[i] = 0;
					}
				}
				else {
					if (i == 0) {
						break;
					}
					placement[i] = null;
					i--;
					remove(units.get(i), placement[i]);
					placement[i] = null;
				}
			}
			if (i == n) {
				best = Arrays.copyOf(placement, n);
				bestPlaced = n;
				compact();
				penalty = totalPenalty();
				return;
			}
			// Not everything fitted: rebuild the fullest attempt, then place the rest wherever they still fit
			for (int k = 0; k < n; k++) {
				if (placement[k] != null) {
					remove(units.get(k), placement[k]);
					placement[k] = null;
				}
			}
			for (int k = 0; k < n; k++) {
				Placement p = best == null ? null : best[k];
				if (p != null && fits(units.get(k), p)) {
					place(units.get(k), p);
					placement[k] = p;
				}
			}
			for (int k = 0; k < n; k++) {
				if (placement[k] == null) {
					List<Placement> c = candidates(units.get(k));
					if (c.isEmpty()) {
						unplaced.add(units.get(k));
					}
					else {
						place(units.get(k), c.getFirst());
						placement[k] = c.getFirst();
					}
				}
			}
			compact();
			penalty = totalPenalty();
		}

		/**
		 * Moves single lessons up into earlier free periods of the same day where the teacher is free too, so a
		 * section's free periods gather at the end of the day. Same day: the spread of subjects is unchanged.
		 */
		private void compact() {
			boolean moved = true;
			for (int round = 0; moved && round < 20; round++) {
				moved = false;
				for (int k = 0; k < units.size(); k++) {
					Unit u = units.get(k);
					Placement p = placement[k];
					if (p == null || u.size() != 1) {
						continue;
					}
					for (int h : days.get(p.day()).periods()) {
						if (h >= p.start()) {
							break;
						}
						Placement earlier = new Placement(p.day(), h);
						if (fits(u, earlier)) {
							remove(u, p);
							place(u, earlier);
							placement[k] = earlier;
							moved = true;
							break;
						}
					}
				}
			}
		}

		/** Free slots for a lesson, best first. */
		private List<Placement> candidates(Unit u) {
			List<Placement> out = new ArrayList<>();
			List<Double> scores = new ArrayList<>();
			for (int d = 0; d < days.size(); d++) {
				int[] held = days.get(d).periods();
				for (int h = 0; h + u.size() - 1 < held.length; h++) {
					int start = held[h];
					if (u.size() == 2 && (held[h + 1] != start + 1 || !pairsWithNext[start])) {
						continue;
					}
					Placement p = new Placement(d, start);
					if (fits(u, p)) {
						out.add(p);
						scores.add(score(u, p) + random.nextDouble() * 0.5);
					}
				}
			}
			Integer[] idx = new Integer[out.size()];
			for (int k = 0; k < idx.length; k++) {
				idx[k] = k;
			}
			Arrays.sort(idx, Comparator.comparingDouble(scores::get));
			List<Placement> sorted = new ArrayList<>(out.size());
			for (Integer k : idx) {
				sorted.add(out.get(k));
			}
			return sorted;
		}

		private boolean fits(Unit u, Placement p) {
			for (int k = 0; k < u.size(); k++) {
				int period = p.start() + k;
				if (period >= periodCount || grid.sectionBusy[u.section()][p.day()][period]) {
					return false;
				}
				if (u.teacher() != null && grid.teacherBusy[u.teacher().index()][p.day()][period]) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Lower is better: spread each subject over the week, lessons early in the day, class teacher first, no long runs
		 * for a teacher.
		 */
		private double score(Unit u, Placement p) {
			double s = grid.subjectOnDay[u.section()][u.subjectIndex()][p.day()] * 10;
			// Lessons early in the day, so free periods gather at the end
			s += p.start() * 0.8;
			Long classTeacher = sections.get(u.section()).classTeacher();
			boolean byClassTeacher = u.teacher() != null && classTeacher != null && classTeacher == u.teacher().id();
			if (p.start() == 0) {
				s += byClassTeacher ? -6 : (classTeacher != null ? 2 : 0);
			}
			if (u.teacher() != null) {
				int t = u.teacher().index();
				int run = u.size();
				for (int k = p.start() - 1; k >= 0 && grid.teacherBusy[t][p.day()][k]; k--) {
					run++;
				}
				for (int k = p.start() + u.size(); k < periodCount && grid.teacherBusy[t][p.day()][k]; k++) {
					run++;
				}
				if (run > 3) {
					s += (run - 3) * 4;
				}
				s += grid.teacherOnDay[t][p.day()];
			}
			return s;
		}

		private void place(Unit u, Placement p) {
			for (int k = 0; k < u.size(); k++) {
				grid.sectionBusy[u.section()][p.day()][p.start() + k] = true;
				if (u.teacher() != null) {
					grid.teacherBusy[u.teacher().index()][p.day()][p.start() + k] = true;
				}
			}
			grid.subjectOnDay[u.section()][u.subjectIndex()][p.day()]++;
			if (u.teacher() != null) {
				grid.teacherOnDay[u.teacher().index()][p.day()] += u.size();
			}
		}

		private void remove(Unit u, Placement p) {
			for (int k = 0; k < u.size(); k++) {
				grid.sectionBusy[u.section()][p.day()][p.start() + k] = false;
				if (u.teacher() != null) {
					grid.teacherBusy[u.teacher().index()][p.day()][p.start() + k] = false;
				}
			}
			grid.subjectOnDay[u.section()][u.subjectIndex()][p.day()]--;
			if (u.teacher() != null) {
				grid.teacherOnDay[u.teacher().index()][p.day()] -= u.size();
			}
		}

		private int totalPenalty() {
			int total = 0;
			for (int[][] section : grid.subjectOnDay) {
				for (int[] subject : section) {
					for (int perDay : subject) {
						if (perDay > 1) {
							total += perDay - 1;
						}
					}
				}
			}
			return total;
		}

		int placedPeriods() {
			int n = 0;
			for (int k = 0; k < units.size(); k++) {
				if (placement[k] != null) {
					n += units.get(k).size();
				}
			}
			return n;
		}

		int penalty() {
			return penalty;
		}

		boolean complete() {
			return unplaced.isEmpty() && bestPlaced == units.size();
		}

		List<Unit> unplaced() {
			return unplaced;
		}

		int size() {
			return units.size();
		}

		/** The k-th lesson in this attempt's order, and where it was placed (null: not placed). */
		Unit unit(int k) {
			return units.get(k);
		}

		Placement placementAt(int k) {
			return placement[k];
		}
	}

	@SuppressWarnings("unused")
	private static String dayName(int weekday) {
		return DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}
}
