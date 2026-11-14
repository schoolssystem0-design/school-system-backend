package com.selectcode.sms.shared.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory sliding-window limiter for sign-in, password reset and public forms. Per instance: fine for the
 * single backend instance in v1; replace with a shared store if the backend is scaled out.
 */
@Component
public class RateLimiter {

	private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

	private final Clock clock;

	public RateLimiter(java.util.Optional<Clock> clock) {
		this.clock = clock.orElse(Clock.systemUTC());
	}

	/** Records a hit and throws 429 {@code RATE_LIMITED} if {@code key} exceeded {@code max} hits within {@code window}. */
	public void check(String key, int max, Duration window) {
		Instant now = clock.instant();
		Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (deque) {
			Instant cutoff = now.minus(window);
			while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
				deque.pollFirst();
			}
			if (deque.size() >= max) {
				throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
						"Too many attempts. Please wait a few minutes and try again.");
			}
			deque.addLast(now);
		}
		if (hits.size() > 50_000) {
			hits.clear(); // crude memory bound under abuse
		}
	}

	/** For tests. */
	public void reset() {
		hits.clear();
	}
}
