package com.selectcode.sms.reports.app;

import com.selectcode.sms.reports.app.ReportDoc.Column;
import com.selectcode.sms.reports.app.ReportDoc.Kind;
import com.selectcode.sms.reports.app.ReportDoc.Section;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.pdf.LetterheadSource;
import com.selectcode.sms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns reports into PDF and Excel files, and any table on screen into the same files (FR 15.6). Exports are audited. */
@Service
public class ReportFiles {

	public record ExportTable(String title, List<String> columns, List<List<String>> rows) {
	}

	private static final int MAX_ROWS = 20_000;

	private final LetterheadSource letterhead;

	private final AuditService audit;

	ReportFiles(LetterheadSource letterhead, AuditService audit) {
		this.letterhead = letterhead;
		this.audit = audit;
	}

	@Transactional
	public byte[] render(ReportDoc doc, String format) {
		audit.record("REPORT_EXPORTED", "report", null, null, Map.of("title", doc.title(), "format", format));
		return switch (format) {
			case "pdf" -> ReportRenderer.pdf(doc, letterhead.current());
			case "xlsx" -> ReportRenderer.xlsx(doc, false);
			default -> throw ApiException.businessRule("FORMAT_INVALID", "Choose PDF or Excel");
		};
	}

	/**
	 * Exports tables as they stand on screen in a detailed report (FR 15.5). The caller sends what it was already allowed
	 * to see, so this reads nothing from the database.
	 */
	@Transactional
	public byte[] tables(String title, List<String> filters, List<ExportTable> tables, String format) {
		if (tables.stream().mapToInt(t -> t.rows().size()).sum() > MAX_ROWS) {
			throw ApiException.businessRule("TOO_MANY", "Export at most 20,000 rows at a time");
		}
		List<Section> sections = tables.stream().map(t -> new Section(t.title() == null || t.title().isBlank() ? title : t.title(), null,
				t.columns().stream().map(c -> new Column(c, Kind.AUTO)).toList(), t.rows())).toList();
		return render(new ReportDoc(title, filters, sections), format);
	}
}
