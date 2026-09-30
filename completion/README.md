# Editor completion

The browser provides immediate local suggestions and requests semantic completion
from the authenticated, CSRF-protected `POST /api/editor/completions` endpoint.
Java uses Eclipse JDT LS with the Java 8 runtime, C++ uses clangd with C++17 standard
headers, and Python 3.12 uses Jedi. Source is parsed, never compiled into or run as
a user program. Class members, inheritance, standard libraries, local functions,
return types, and C++ pointer/namespace access are resolved by these engines.

The private `completion` Compose service has no published port, host volume,
credentials, or outbound network. Analysis workspaces live in tmpfs. There is one
bounded worker per language; changing accounts destroys the old worker and its
workspace. Idle workers expire after two minutes. Requests are limited to 64 KiB,
three concurrent backend requests, and one per account. A cold Java worker can take
about ten seconds to initialize. Local suggestions remain available during startup
or service failure. This is single-file completion with installed standard libraries;
external project dependencies and dynamically generated Python members are not
promised. The service does not provide IDE refactoring or project navigation.

Build alongside the application:

```sh
docker build -f completion/Dockerfile -t gamjaoj-completion:20260930 .
```

Compose connects the application to the private service through `COMPLETION_URL`.
Outside Compose, leaving `COMPLETION_URL` unset retains local-only completion.
`scripts/deploy-web.sh` builds and uses a release-specific service image. Retain the
image name with the application release when rolling back. No database migration
is required.

Validation, against an isolated instance of the image:

```sh
docker exec -i YOUR_TEST_CONTAINER python - < completion/test_engines.py
cd frontend
GAMJAOJ_COMPLETION_TEST_CONTAINER=YOUR_TEST_CONTAINER npx playwright test tests/semantic-live.spec.mjs --workers=1
npx playwright test tests/editor-console.spec.mjs tests/languages.spec.mjs --workers=1
```

Backend `CompletionIntegrationTest` checks session/CSRF protection, server-owned
account identity, request limits, UTF-16 cursor offsets, and proxy forwarding.
The browser only applies inert text edits to the exact document version analyzed.
