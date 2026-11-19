package com.tirvextech.sms.shared.verify;

import com.tirvextech.sms.shared.security.PublicEndpoint;
import com.tirvextech.sms.shared.verify.DocumentVerificationService.VerifyResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Anyone with the QR code from a printed document can confirm it was issued by the school. */
@RestController
class PublicVerifyController {

	private final DocumentVerificationService verification;

	PublicVerifyController(DocumentVerificationService verification) {
		this.verification = verification;
	}

	@PublicEndpoint
	@GetMapping("/api/v1/public/verify/{token}")
	VerifyResult verify(@PathVariable String token) {
		return verification.verify(token).orElseGet(
				() -> new VerifyResult(false, null, null, null, "This verification code is not recognised.", null));
	}
}