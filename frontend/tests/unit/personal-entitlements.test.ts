import { describe, expect, it } from "vitest";
import {
  canManagePersonalClasses,
  parsePersonalWorkspace,
} from "@/features/subscriptions/personal-entitlements";

const personalCoGoi = {
  id: "w-personal",
  kind: "PERSONAL",
  name: "Cá nhân",
  subscription: { effective: true, expiresAt: "2027-02-07T00:00:00Z" },
  roles: [],
  syncState: "SYNCED",
};

const personalHetGoi = {
  id: "w-personal",
  kind: "PERSONAL",
  name: "Cá nhân",
  subscription: { effective: false },
  roles: [],
  syncState: "SYNCED",
};

const center = {
  id: "w-center",
  kind: "CENTER",
  name: "Trung tâm",
  subscription: { effective: true },
  roles: ["TEACHER"],
  syncState: "SYNCED",
};

describe("parsePersonalWorkspace", () => {
  it("chỉ lấy workspace PERSONAL, bỏ qua CENTER và bản ghi lệch", () => {
    expect(parsePersonalWorkspace([center, personalCoGoi])).toEqual(personalCoGoi);
    expect(parsePersonalWorkspace([center])).toBeNull();
    expect(parsePersonalWorkspace("khong-phai-danh-sach")).toBeNull();
    expect(parsePersonalWorkspace([{ id: "w", kind: "PERSONAL" }])).toBeNull();
  });

  it("giữ đúng trạng thái gói: hiệu lực kèm hạn, hết hạn thì effective=false", () => {
    expect(parsePersonalWorkspace([personalHetGoi])?.subscription.effective).toBe(false);
    expect(parsePersonalWorkspace([personalCoGoi])?.subscription.expiresAt)
      .toBe("2027-02-07T00:00:00Z");
  });
});

describe("canManagePersonalClasses", () => {
  it("chỉ cho quản lý lớp cá nhân khi có workspace PERSONAL và gói còn hiệu lực", () => {
    expect(canManagePersonalClasses(parsePersonalWorkspace([personalCoGoi]))).toBe(true);
    expect(canManagePersonalClasses(parsePersonalWorkspace([personalHetGoi]))).toBe(false);
    expect(canManagePersonalClasses(parsePersonalWorkspace([center]))).toBe(false);
    expect(canManagePersonalClasses(null)).toBe(false);
  });
});
