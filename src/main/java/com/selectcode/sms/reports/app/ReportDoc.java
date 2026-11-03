package com.selectcode.sms.reports.app;

import java.util.List;

/**
 * A finished report. The screen, the PDF and the Excel file are all drawn from this one object, so their values cannot
 * differ (QA-RPT-004). Values are text as the API sends them; {@link Kind} says how Excel should store them.
 */
public record ReportDoc(String title, List<String> heading, List<Section> sections) {

	public enum Kind {
		TEXT, NUMBER, MONEY, PERCENT, AUTO
	}

	public record Column(String name, Kind kind) {
	}

	public record Section(String title, String note, List<Column> columns, List<List<String>> rows) {
	}

	public static Column text(String name) {
		return new Column(name, Kind.TEXT);
	}

	public static Column number(String name) {
		return new Column(name, Kind.NUMBER);
	}

	public static Column money(String name) {
		return new Column(name, Kind.MONEY);
	}

	public static Column percent(String name) {
		return new Column(name, Kind.PERCENT);
	}
}
