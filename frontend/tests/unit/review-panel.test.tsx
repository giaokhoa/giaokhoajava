import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  CorrectionNoticeView,
  parseCorrectionNotice,
} from "../../src/features/resources/correction-notice";
import {
  buildSampleActionPayload,
  parseSampleActionResult,
  sampleActionAttemptSignature,
  SAMPLE_ACTION_SPECS,
} from "../../src/features/resources/sample-actions-panel";

describe("danh mục sample action của panel review", () => {
  it("đủ 16 discriminator cố định của hợp đồng", () => {
    expect(SAMPLE_ACTION_SPECS.map((spec) => spec.action)).toEqual([
      "PRACTICE_SYSTEM_VERSION",
      "CREATE_RESOURCE",
      "SAVE_VERSION",
      "FORK_VERSION",
      "SHARE_VERSION",
      "ACCEPT_SHARE",
      "REVOKE_SHARE",
      "RECEIVE_UPDATE",
      "CORRECT_SOURCE",
      "ASSIGN_ACTIVITY",
      "SUBMIT_ACTIVITY",
      "GRADE_RESULT",
      "PUBLISH_RESULT",
      "EXTEND_DEADLINE",
      "OPEN_ATTEMPT",
      "REASSIGN_ACTIVITY",
    ]);
  });

  it("mỗi action mang đúng các trường hợp đồng mà E2E điền", () => {
    const fields = (action: string) =>
      SAMPLE_ACTION_SPECS.find((spec) => spec.action === action)?.fields.map((field) => field.name);
    expect(fields("SAVE_VERSION")).toEqual(["resourceId", "versionId"]);
    expect(fields("SHARE_VERSION")).toContain("recipientWorkspaceId");
    expect(fields("SHARE_VERSION")).toContain("mode");
    expect(fields("SHARE_VERSION")).toContain("actions");
    expect(fields("ACCEPT_SHARE")).toEqual(["grantId"]);
    expect(fields("REVOKE_SHARE")).toEqual(["grantId"]);
    expect(fields("RECEIVE_UPDATE")).toEqual(["libraryItemId", "replacementVersionId"]);
    expect(fields("CORRECT_SOURCE")).toEqual([
      "resourceId",
      "affectedVersionId",
      "replacementVersionId",
      "reason",
    ]);
    expect(fields("ASSIGN_ACTIVITY")).toContain("courseSubjectId");
    expect(fields("ASSIGN_ACTIVITY")).toContain("attemptLimit");
  });
});

describe("dựng payload sample action", () => {
  it("chỉ gửi trường của action đã chọn và bỏ trường rỗng", () => {
    const payload = buildSampleActionPayload("SAVE_VERSION", {
      resourceId: "r-1",
      versionId: "v-1",
      grantId: "g-1",
      label: "",
    });
    expect(payload).toEqual({ action: "SAVE_VERSION", resourceId: "r-1", versionId: "v-1" });
  });

  it("attemptLimit thành số và allowedWorkspaceIds thành mảng", () => {
    const payload = buildSampleActionPayload("SHARE_VERSION", {
      resourceId: "r-1",
      versionId: "v-1",
      recipientWorkspaceId: "w-2",
      mode: "REVOCABLE",
      actions: "use,content.read",
      allowedWorkspaceIds: "w-2, w-3",
    });
    expect(payload.actions).toBe("use,content.read");
    expect(payload.allowedWorkspaceIds).toEqual(["w-2", "w-3"]);

    const assign = buildSampleActionPayload("ASSIGN_ACTIVITY", {
      courseSubjectId: "cs-1",
      resourceId: "r-1",
      versionId: "v-1",
      dueAt: "2026-11-17T00:00:00.000Z",
      attemptLimit: "1",
    });
    expect(assign.attemptLimit).toBe(1);
  });
});

describe("chữ ký attempt của action mẫu", () => {
  it("cùng workspace + cùng payload dùng một chữ ký, khác workspace hoặc payload thì khác", () => {
    const payload = buildSampleActionPayload("SAVE_VERSION", { resourceId: "r-1", versionId: "v-1" });
    const same = sampleActionAttemptSignature("w-1", payload);
    expect(
      sampleActionAttemptSignature(
        "w-1",
        buildSampleActionPayload("SAVE_VERSION", { resourceId: "r-1", versionId: "v-1" }),
      ),
    ).toBe(same);
    expect(sampleActionAttemptSignature("w-2", payload)).not.toBe(same);
    expect(
      sampleActionAttemptSignature(
        "w-1",
        buildSampleActionPayload("SAVE_VERSION", { resourceId: "r-1", versionId: "v-2" }),
      ),
    ).not.toBe(same);
  });
});

describe("đọc kết quả sample action", () => {
  it("nhận kết quả đồng bộ và giữ định danh trả về để hiển thị", () => {
    const parsed = parseSampleActionResult({
      id: "op-1",
      state: "SUCCEEDED",
      completed: true,
      targetType: "LIBRARY_ITEM",
      targetId: "li-1",
    });
    expect(parsed?.state).toBe("SUCCEEDED");
    expect(parsed?.completed).toBe(true);
    expect(parsed?.entries).toContainEqual(["targetId", "li-1"]);
  });

  it("từ chối payload không có state/completed", () => {
    expect(parseSampleActionResult({ id: "op-1" })).toBeNull();
    expect(parseSampleActionResult("SUCCEEDED")).toBeNull();
  });
});

describe("thông báo đính chính", () => {
  const notice = {
    resourceId: "r-1",
    affectedVersionId: "v-1",
    replacementVersionId: "v-2",
    reason: "Sai đáp án câu 1",
  };

  it("yêu cầu đủ ba định danh của hợp đồng", () => {
    expect(parseCorrectionNotice(notice)?.replacementVersionId).toBe("v-2");
    expect(parseCorrectionNotice({ ...notice, replacementVersionId: "" })).toBeNull();
    expect(parseCorrectionNotice(null)).toBeNull();
  });

  it("hiển thị lý do và trạng thái chưa nhận cập nhật", () => {
    const parsed = parseCorrectionNotice(notice);
    expect(parsed).not.toBeNull();
    const html = renderToStaticMarkup(
      createElement(CorrectionNoticeView, { notice: parsed!, updateReceived: false }),
    );
    expect(html).toContain("Sai đáp án câu 1");
    expect(html).toContain("Chưa nhận cập nhật");
    expect(html).toContain("v-2");
  });
});
