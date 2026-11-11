package com.giaokhoa.verification.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Đáp án của một resource version qua route riêng với scope riêng. */
public record ResourceAnswerView(UUID resourceId, UUID versionId, @Nullable String answer) {}
