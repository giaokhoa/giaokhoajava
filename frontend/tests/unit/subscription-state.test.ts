import { describe, expect, it } from "vitest";
import {
  blockedActionReason,
  isExpansionBlocked,
  readOnlyNotice,
  sampleActionExpiryBehavior,
  subscriptionPhase,
} from "@/features/subscriptions/subscription-state";
import type { WorkspaceView } from "@/features/identity/workspace-switcher";

const centerConGoi: WorkspaceView = {
  id: "w-center",
  kind: "CENTER",
  name: "Trung tâm",
  subscription: { effective: true, expiresAt: "2027-02-07T00:00:00Z" },
  roles: ["OWNER"],
  syncState: "SYNCED",
};

const centerHetGoi: WorkspaceView = {
  ...centerConGoi,
  subscription: { effective: false },
};

const personalHetGoi: WorkspaceView = {
  id: "w-personal",
  kind: "PERSONAL",
  name: "Cá nhân",
  subscription: { effective: false },
  roles: [],
  syncState: "SYNCED",
};

const heThong: WorkspaceView = {
  id: "w-system",
  kind: "SYSTEM",
  name: "Hệ thống",
  subscription: { effective: false },
  roles: [],
  syncState: "SYNCED",
};

describe("subscriptionPhase", () => {
  it("gói còn hiệu lực thì EFFECTIVE và không chặn mở rộng", () => {
    expect(subscriptionPhase(centerConGoi)).toBe("EFFECTIVE");
    expect(isExpansionBlocked(centerConGoi)).toBe(false);
  });

  it("gói không còn hiệu lực thì LAPSED và chặn mở rộng ở cả center lẫn personal", () => {
    expect(subscriptionPhase(centerHetGoi)).toBe("LAPSED");
    expect(isExpansionBlocked(centerHetGoi)).toBe(true);
    expect(isExpansionBlocked(personalHetGoi)).toBe(true);
  });
});

describe("readOnlyNotice", () => {
  it("gói còn hiệu lực thì không có thông báo chỉ đọc", () => {
    expect(readOnlyNotice(centerConGoi)).toBeNull();
  });

  it("nói rõ dữ liệu được giữ, hoàn tất và thu hồi vẫn được, theo đúng loại workspace", () => {
    expect(readOnlyNotice(centerHetGoi)).toContain("trung tâm");
    expect(readOnlyNotice(centerHetGoi)).toContain("chỉ đọc");
    expect(readOnlyNotice(centerHetGoi)).toContain("hoàn tất bài đã giao");
    expect(readOnlyNotice(centerHetGoi)).toContain("thu hồi");
    expect(readOnlyNotice(personalHetGoi)).toContain("cá nhân");
  });

  it("workspace hệ thống không theo gói thì không có thông báo", () => {
    expect(readOnlyNotice(heThong)).toBeNull();
  });
});

describe("sampleActionExpiryBehavior", () => {
  it("hoàn tất bài đã giao và thu hồi vẫn dùng được khi gói hết hạn", () => {
    for (const action of ["SUBMIT_ACTIVITY", "GRADE_RESULT", "PUBLISH_RESULT", "REVOKE_SHARE"]) {
      expect(sampleActionExpiryBehavior(action)).toBe("ALLOWED_WHEN_LAPSED");
    }
  });

  it("luyện tập hệ thống và nhận cập nhật theo quyền đã cấp vẫn dùng được khi gói hết hạn", () => {
    expect(sampleActionExpiryBehavior("PRACTICE_SYSTEM_VERSION")).toBe("ALLOWED_WHEN_LAPSED");
    expect(sampleActionExpiryBehavior("RECEIVE_UPDATE")).toBe("ALLOWED_WHEN_LAPSED");
  });

  it("mọi thao tác mở rộng bị chặn khi gói hết hạn, kể cả action lạ", () => {
    for (const action of [
      "CREATE_RESOURCE",
      "SAVE_VERSION",
      "FORK_VERSION",
      "SHARE_VERSION",
      "ACCEPT_SHARE",
      "ASSIGN_ACTIVITY",
      "EXTEND_DEADLINE",
      "OPEN_ATTEMPT",
      "REASSIGN_ACTIVITY",
      "CORRECT_SOURCE",
      "ACTION_LA",
    ]) {
      expect(sampleActionExpiryBehavior(action)).toBe("BLOCKED_WHEN_LAPSED");
    }
  });
});

describe("blockedActionReason", () => {
  it("SUBSCRIPTION_INACTIVE có lý do dễ hiểu nói rõ phần vẫn dùng được", () => {
    const reason = blockedActionReason("SUBSCRIPTION_INACTIVE");
    expect(reason).toContain("không còn hiệu lực");
    expect(reason).toContain("hoàn tất bài đã giao");
    expect(reason).toContain("thu hồi");
  });

  it("mã lạ hoặc thiếu mã thì trả null để nơi gọi giữ thông điệp gốc", () => {
    expect(blockedActionReason("PERMISSION_DENIED")).toBeNull();
    expect(blockedActionReason(undefined)).toBeNull();
  });
});
