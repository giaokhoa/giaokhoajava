package com.giaokhoa.resources.repository;

import java.util.List;
import java.util.UUID;

/** Một grant đã ACCEPTED trỏ tới đúng version, làm căn cứ license cho lưu/fork và trần quyền. */
public record GrantRef(UUID id, List<String> actions, List<UUID> allowedWorkspaceIds) {
    public GrantRef {
        actions = List.copyOf(actions);
        allowedWorkspaceIds = List.copyOf(allowedWorkspaceIds);
    }
}
