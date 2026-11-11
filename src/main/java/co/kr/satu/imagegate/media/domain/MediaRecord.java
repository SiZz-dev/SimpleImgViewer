package co.kr.satu.imagegate.media.domain;

import java.time.Instant;

public record MediaRecord(String mediaId, String tenantId, String ownerId, String mediaVersion,
        String fileName, MediaType mediaType, String mimeType, long sizeBytes,
        String sha256, String storedFileName, Instant createdAt) {}
