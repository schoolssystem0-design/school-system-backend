package com.selectcode.sms.shared.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileStorageConfig.FileStorageProperties.class)
class FileStorageConfig {

	private static final Logger log = LoggerFactory.getLogger(FileStorageConfig.class);

	/**
	 * @param localDir directory used when R2 is not configured (development and tests)
	 * @param r2Endpoint R2 S3 endpoint, e.g. https://&lt;account&gt;.r2.cloudflarestorage.com
	 */
	@ConfigurationProperties("app.files")
	record FileStorageProperties(String localDir, String r2Endpoint, String r2AccessKeyId, String r2SecretAccessKey,
			String r2Bucket) {
	}

	@Bean
	FileStorage fileStorage(FileStorageProperties properties, Environment environment) {
		if (StringUtils.hasText(properties.r2Bucket())) {
			return new R2FileStorage(properties);
		}
		if (environment.acceptsProfiles(Profiles.of("prod", "staging"))) {
			throw new IllegalStateException("R2 file storage must be configured in staging/production (app.files.r2-*)");
		}
		Path dir = Path.of(StringUtils.hasText(properties.localDir()) ? properties.localDir()
				: System.getProperty("java.io.tmpdir") + "/sms-files");
		log.info("Using local file storage at {}", dir.toAbsolutePath());
		return new LocalFileStorage(dir);
	}

	static final class LocalFileStorage implements FileStorage {

		private final Path root;

		LocalFileStorage(Path root) {
			this.root = root.toAbsolutePath().normalize();
		}

		private Path resolve(String key) {
			Path path = root.resolve(key).normalize();
			if (!path.startsWith(root)) {
				throw new IllegalArgumentException("Invalid storage key");
			}
			return path;
		}

		@Override
		public void put(String key, byte[] content, String contentType) {
			try {
				Path path = resolve(key);
				Files.createDirectories(path.getParent());
				Files.write(path, content);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public byte[] get(String key) {
			try {
				return Files.readAllBytes(resolve(key));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public Optional<URI> presignedGetUrl(String key, Duration validity, String downloadName) {
			return Optional.empty();
		}
	}

	static final class R2FileStorage implements FileStorage {

		private final S3Client client;

		private final S3Presigner presigner;

		private final String bucket;

		R2FileStorage(FileStorageProperties properties) {
			var credentials = StaticCredentialsProvider.create(
					AwsBasicCredentials.create(properties.r2AccessKeyId(), properties.r2SecretAccessKey()));
			URI endpoint = URI.create(properties.r2Endpoint());
			var s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
			this.client = S3Client.builder()
				.endpointOverride(endpoint)
				.region(Region.of("auto"))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.build();
			this.presigner = S3Presigner.builder()
				.endpointOverride(endpoint)
				.region(Region.of("auto"))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.build();
			this.bucket = properties.r2Bucket();
		}

		@Override
		public void put(String key, byte[] content, String contentType) {
			client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
		}

		@Override
		public byte[] get(String key) {
			return client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
		}

		@Override
		public Optional<URI> presignedGetUrl(String key, Duration validity, String downloadName) {
			var request = presigner.presignGetObject(p -> p.signatureDuration(validity)
				.getObjectRequest(g -> g.bucket(bucket)
					.key(key)
					.responseContentDisposition("attachment; filename=\"" + downloadName.replace("\"", "") + "\"")));
			try {
				return Optional.of(request.url().toURI());
			}
			catch (java.net.URISyntaxException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}
}
