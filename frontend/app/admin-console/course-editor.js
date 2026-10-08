"use client";
import { useState } from "react";
import Modal from "../modal";
import { api, write } from "./api";
import { Feedback } from "./shared";
export default function CourseEditor({ initial, onClose, onSaved }) {
  const [form, setForm] = useState({
      ...initial,
      stages: initial.stages.map((s) => ({
        ...s,
        versions: s.versions.join("\n"),
      })),
      reason: "",
    }),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  function field(name, value) {
    setForm((f) => ({ ...f, [name]: value }));
  }
  function stage(index, name, value) {
    setForm((f) => ({
      ...f,
      stages: f.stages.map((s, i) =>
        i === index ? { ...s, [name]: value } : s,
      ),
    }));
  }
  async function save(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const { new: isNew, ...body } = form;
      body.stages = body.stages.map((s) => ({
        ...s,
        versions: s.versions
          .split(/[\n,]+/)
          .map((v) => v.trim())
          .filter(Boolean),
      }));
      await write("/api/admin/courses", body, isNew ? "POST" : "PUT");
      await onSaved();
      onClose();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      open
      title={initial.new ? "새 훈련 코스" : "코스 구성 편집"}
      wide
      onClose={() => {
        if (!busy) onClose();
      }}
      description="새 코스는 비공개로 저장됩니다. 기존 수강자의 구성은 유지되고 새 등록부터 변경됩니다."
    >
      <form className="control-form" onSubmit={save}>
        <label>
          코스 ID
          <input
            required
            pattern="[a-z0-9][a-z0-9-]{2,79}"
            disabled={!initial.new}
            value={form.id}
            maxLength={80}
            onChange={(e) => field("id", e.target.value)}
          />
        </label>
        <div className="control-form-columns">
          <label>
            종류
            <input
              required
              maxLength={40}
              value={form.kind}
              onChange={(e) => field("kind", e.target.value)}
            />
          </label>
          <label>
            제목
            <input
              required
              maxLength={120}
              value={form.title}
              onChange={(e) => field("title", e.target.value)}
            />
          </label>
        </div>
        {[
          ["summary", "코스 소개"],
          ["prerequisite", "선수 지식"],
          ["notice", "안내"],
        ].map(([key, label]) => (
          <label key={key}>
            {label}
            <textarea
              required={key === "summary"}
              maxLength={500}
              rows={2}
              value={form[key]}
              onChange={(e) => field(key, e.target.value)}
            />
          </label>
        ))}
        {form.stages.map((s, i) => (
          <fieldset className="control-stage" key={i}>
            <legend>단계 {i + 1}</legend>
            <label>
              단계 이름
              <input
                required
                maxLength={120}
                value={s.title}
                onChange={(e) => stage(i, "title", e.target.value)}
              />
            </label>
            <label>
              학습 목표
              <input
                required
                maxLength={120}
                value={s.goal}
                onChange={(e) => stage(i, "goal", e.target.value)}
              />
            </label>
            <label>
              문제 ID · 줄마다 하나씩
              <textarea
                required
                rows={4}
                value={s.versions}
                onChange={(e) => stage(i, "versions", e.target.value)}
              />
            </label>
            {form.stages.length > 1 && (
              <button
                type="button"
                className="secondary"
                onClick={() =>
                  field(
                    "stages",
                    form.stages.filter((_, n) => n !== i),
                  )
                }
              >
                이 단계 삭제
              </button>
            )}
          </fieldset>
        ))}
        <button
          type="button"
          className="secondary"
          disabled={form.stages.length >= 12}
          onClick={() =>
            field("stages", [
              ...form.stages,
              { title: "", goal: "", versions: "" },
            ])
          }
        >
          단계 추가
        </button>
        <label>
          변경 사유
          <textarea
            required
            minLength={4}
            maxLength={500}
            rows={2}
            value={form.reason}
            onChange={(e) => field("reason", e.target.value)}
          />
        </label>
        <Feedback error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "저장 중…" : "코스 저장"}
        </button>
      </form>
    </Modal>
  );
}
