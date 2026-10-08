package dev.gamjaoj.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JudgeJob(
    UUID submissionId,
    String status,
    int attempt,
    UUID token,
    UUID workerId,
    OffsetDateTime leaseUntil,
    String verdict,
    String resultJson,
    String resultSha256,
    String executionMode) {}
