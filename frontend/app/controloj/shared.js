"use client";
export const date = (value) =>
  value ? new Date(value).toLocaleString("ko-KR") : "—";
export const statusLabels = {
  QUEUED: "대기",
  RUNNING: "처리 중",
  FINISHED: "완료",
  FAILED: "실패",
  READY: "준비 완료",
  COMPLETED: "완료",
  CANCELLED: "취소",
  ACTIVE: "진행 중",
  ENDED: "마무리",
  UNKNOWN: "결과 미확정",
  HELD: "보류",
  HELD_DISABLED: "기능 비활성",
  PAUSED: "일시 중지",
  PUBLISHED: "게시 완료",
  DEADLINE_EXCEEDED: "기한 초과",
};
export const status = (value) => statusLabels[value] || value;
export function Feedback({ error, message }) {
  return (
    <>
      {error && (
        <p className="control-error" role="alert">
          {error}
        </p>
      )}
      {message && (
        <p className="control-feedback" role="status">
          {message}
        </p>
      )}
    </>
  );
}
export function parseJSON(value) {
  try {
    return JSON.parse(value || "{}");
  } catch {
    return value || {};
  }
}
export function parseTags(value) {
  return typeof value === "string" ? value.split(",").filter(Boolean) : [];
}
