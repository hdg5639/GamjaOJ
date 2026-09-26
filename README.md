# GamjaOJ

A personal coding-practice platform with optional diagnostics, guided training,
AI-assisted problem generation, and isolated code judging.

## Features

- Java, C++ and Python submissions with language-specific execution limits.
- Optional diagnostic assessments using reviewed problem banks.
- Personalized practice plans and problem generation.
- Problem browsing with category tags and difficulty labels.
- Code completion, adjustable editor layouts, and per-problem submission history.
- Personal learning history and account settings.

## Technology

Spring Boot, Next.js, PostgreSQL, and a dedicated Docker-based judging worker.

## Local development

Requirements: Java 21, Node.js, Python 3.9+, and Docker where isolated judging is needed.

```sh
./scripts/build-web.sh
python3 -m unittest discover -s tests
```

Infrastructure addresses, SSH configuration, deployment details, credentials, and
operational reports are maintained outside the public repository.
Do not commit environment files, access tokens, internal hostnames, or private network addresses.

Operational tools require explicit local configuration through `GAMJAOJ_APP_SSH_TARGET`,
`GAMJAOJ_RUNNER_SSH_TARGET`, and `GAMJAOJ_BASE_URL` where applicable.
The AI settings tool requires an explicit `--target`. Keep actual values in an ignored environment file.
