package com.giaokhoa.tenancy;

import com.giaokhoa.tenancy.model.CenterMembership;
import com.giaokhoa.tenancy.model.MembershipIndexEntry;
import com.giaokhoa.tenancy.repository.MembershipIndexEntryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Đồng bộ chỉ mục membership ở schema hệ thống theo membership nguồn vừa ghi. Mọi đường ghi
 * membership (tạo center, chấp nhận lời mời, cấp trực tiếp, thu hồi) đều qua đây trong cùng
 * transaction để chỉ mục không bao giờ lệch khỏi trạng thái membership hiện hành.
 */
@Component
public class MembershipIndexSynchronizer {
    private final MembershipIndexEntryRepository index;

    public MembershipIndexSynchronizer(MembershipIndexEntryRepository index) {
        this.index = index;
    }

    public void sync(UUID userId, UUID workspaceId, CenterMembership.State state) {
        MembershipIndexEntry.Key key = new MembershipIndexEntry.Key(userId, workspaceId);
        index.findById(key)
                .ifPresentOrElse(
                        entry -> entry.syncState(state),
                        () -> index.save(new MembershipIndexEntry(userId, workspaceId, state)));
    }
}
