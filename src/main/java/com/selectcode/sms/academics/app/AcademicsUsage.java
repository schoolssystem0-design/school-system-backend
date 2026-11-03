package com.selectcode.sms.academics.app;

import com.selectcode.sms.academics.infra.ClassSubjectRepository;
import com.selectcode.sms.academics.infra.SectionRepository;
import com.selectcode.sms.shared.usage.UsageContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/** Where-used counts owned by the academics module itself. */
@Component
class AcademicsUsage implements UsageContributor {

	private final SectionRepository sections;

	private final ClassSubjectRepository classSubjects;

	AcademicsUsage(SectionRepository sections, ClassSubjectRepository classSubjects) {
		this.sections = sections;
		this.classSubjects = classSubjects;
	}

	@Override
	public List<Usage> usages(Target target, long id) {
		return switch (target) {
			case CLASS -> List.of(new Usage("Sections", sections.countByClassId(id)),
					new Usage("Class subjects", classSubjects.countByClassId(id)));
			case SUBJECT -> List.of(new Usage("Classes", classSubjects.countBySubjectId(id)));
			case SECTION -> List.of();
		};
	}
}
