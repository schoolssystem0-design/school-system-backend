package com.selectcode.sms.platform.app;

import com.selectcode.sms.platform.PlatformPrincipal;
import com.selectcode.sms.platform.domain.School;
import com.selectcode.sms.shared.audit.AuditService;
import com.selectcode.sms.shared.security.CurrentActor;
import com.selectcode.sms.shared.security.StepUp;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * "Open school" for the super admin (P-7): stores the school in the super admin's session, after which every school
 * API works in that school with every permission. Each switch is audited.
 */
@Service
public class ActiveSchoolService {

	private final SchoolManagementService schools;

	private final SecurityContextRepository securityContextRepository;

	private final AuditService audit;

	private final TransactionTemplate transaction;

	ActiveSchoolService(SchoolManagementService schools, SecurityContextRepository securityContextRepository,
			AuditService audit, PlatformTransactionManager transactionManager) {
		this.schools = schools;
		this.securityContextRepository = securityContextRepository;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public PlatformPrincipal open(long schoolId, HttpServletRequest request, HttpServletResponse response) {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		School school = transaction.execute(tx -> {
			School s = schools.find(schoolId);
			audit.recordPlatform("SWITCH_SCHOOL", schoolId, Map.of("code", s.getCode()));
			return s;
		});
		PlatformPrincipal next = current.withActiveSchool(school.getId(), school.getCode(), school.getName());
		save(next, request, response);
		return next;
	}

	public PlatformPrincipal leave(HttpServletRequest request, HttpServletResponse response) {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		PlatformPrincipal next = current.withoutActiveSchool();
		save(next, request, response);
		return next;
	}

	private void save(PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		securityContextRepository.saveContext(context, request, response);
		// A step-up made in one school must not carry over to another.
		StepUp.clear(request.getSession(false));
	}
}
