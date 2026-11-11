package com.giaokhoa.verification.dto;

import java.util.UUID;

/** Thân bài của một resource version: đáp án không bao giờ nằm trong đáp án của route này. */
public record ResourceBodyView(UUID resourceId, UUID versionId, String body) {}
