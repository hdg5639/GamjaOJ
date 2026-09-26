FROM python@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e
COPY codex /usr/local/bin/codex
COPY --chown=65532:65532 generation /opt/gamjaoj/generation
WORKDIR /opt/gamjaoj
USER 65532:65532
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 GENERATION_CODEX_HOME=/auth CODEX_BIN=/usr/local/bin/codex
ENTRYPOINT ["python3", "-m", "generation.worker", "--state", "/state"]
