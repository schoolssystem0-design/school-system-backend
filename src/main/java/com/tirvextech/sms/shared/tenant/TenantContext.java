package com.selectcode.sms.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the school (tenant) of the current request or job.
 *
 * <p>Set only by {@code RequestPrincipalFilter} (from the authenticated session), by sign-in before the user is known,
 * or by platform code for super admin work. Never set it from client input. Always cleared in {@code finally}.
 */
public final class TenantContext {

	private record State(Long schoolId, boolean allSchools) {
	}

	private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static Optional<Long> schoolId() {
		State state = CURRENT.get();
		return state == null ? Optional.empty() : Optional.ofNullable(state.schoolId());
	}

	public static Long requireSchoolId() {
		return schoolId().orElseThrow(() -> new IllegalStateException("No school (tenant) in context"));
	}

	public static boolean isAllSchools() {
		State state = CURRENT.get();
		return state != null && state.allSchools();
	}

	/** For the request filter only. Application code uses {@link #callAsSchool}. */
	public static void set(Long schoolId) {
		CURRENT.set(new State(schoolId, false));
	}

	/** For the request filter only. */
	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} as the given school, restoring the previous context afterwards. */
	public static <T> T callAsSchool(long schoolId, Supplier<T> work) {
		return callWith(new State(schoolId, false), work);
	}

	public static void runAsSchool(long schoolId, Runnable work) {
		callAsSchool(schoolId, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * Runs {@code work} with access to every school. For super admin cross-school endpoints only; callers must have
	 * verified the super admin role first.
	 */
	public static <T> T callAcrossAllSchools(Supplier<T> work) {
		return callWith(new State(null, true), work);
	}

	private static <T> T callWith(State state, Supplier<T> work) {
		State previous = CURRENT.get();
		CURRENT.set(state);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}
}
