package dev.gamjaoj.domain;

import java.util.UUID;

public record VerificationEntry(
    UUID id, UUID jobId, int revision, String snapshotJson, String snapshotSha256) {}
