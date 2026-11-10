"use client";

import {
  operationStatus,
  type TrackedOperation,
} from "../../lib/operation-status";

export function OperationPanel({
  operation,
  checking,
  onCheck,
  titleId = "operation-title",
}: {
  operation: TrackedOperation;
  checking: boolean;
  onCheck: () => void;
  titleId?: string;
}) {
  return (
    <section className="section" aria-labelledby={titleId}>
      <h2 id={titleId}>Trạng thái thao tác</h2>
      <p>{operationStatus(operation).message}</p>
      <p>
        Mã thao tác: <code>{operation.id}</code>
      </p>
      {operation.errorCode ? <p>Mã lỗi: {operation.errorCode}</p> : null}
      <button
        className="secondary-button"
        type="button"
        disabled={checking}
        onClick={onCheck}
      >
        Kiểm tra trạng thái
      </button>
    </section>
  );
}
