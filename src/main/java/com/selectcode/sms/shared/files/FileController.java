package com.selectcode.sms.shared.files;

import com.selectcode.sms.shared.security.AnyAuthenticated;
import com.selectcode.sms.shared.web.ApiException;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
class FileController {

	private final FileService files;

	FileController(FileService files) {
		this.files = files;
	}

	/** Upload: the permission depends on the purpose and is checked in {@link FileService}. */
	@AnyAuthenticated
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	FileService.FileInfo upload(@RequestParam FilePurpose purpose, @RequestParam MultipartFile file)
			throws IOException {
		return files.upload(purpose, file.getOriginalFilename(), file.getBytes());
	}

	/**
	 * Download: any signed-in user of the same school (row-level security). Phase 1 files are the school's identity
	 * assets; owner-specific checks are added with student/staff documents in Phase 2.
	 */
	@AnyAuthenticated
	@GetMapping("/{id}")
	ResponseEntity<byte[]> download(@PathVariable long id) {
		FileService.Download download = files.download(id);
		if (download.redirect().isPresent()) {
			return ResponseEntity.status(HttpStatus.FOUND).location(download.redirect().get()).build();
		}
		if (download.content() == null) {
			throw ApiException.notFound("File");
		}
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(download.file().getContentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(download.file().getOriginalName()).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
			.body(download.content());
	}
}
