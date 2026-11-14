package com.selectcode.sms.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Paged list response (docs/API-CONVENTIONS.md). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}

	public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
		return new PageResponse<>(items, page, size, total, size == 0 ? 0 : (int) Math.ceil((double) total / size));
	}

	/** Clamps client paging input to the documented limits (default 25, max 200). */
	public static int size(Integer requested) {
		return requested == null ? 25 : Math.max(1, Math.min(200, requested));
	}

	public static int page(Integer requested) {
		return requested == null ? 0 : Math.max(0, requested);
	}
}
