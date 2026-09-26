"""Host wall-clock diagnostics only; never used to decide a verdict or a limit."""
from contextlib import contextmanager
from datetime import datetime, timezone
import time
import tempfile


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class Timings:
    def __init__(self):
        self.started_at = utc_now()
        self.started = time.monotonic()
        self.segments = []

    @contextmanager
    def measure(self, phase):
        started_at, started = utc_now(), time.monotonic()
        success = False
        try:
            yield
            success = True
        finally:
            self.segments.append(dict(phase=phase, startedAt=started_at, finishedAt=utc_now(),
                                      elapsedMs=round((time.monotonic()-started)*1000, 3), success=success))

    def call(self, phase, function, *args, **kwargs):
        with self.measure(phase):
            return function(*args, **kwargs)

    def snapshot(self):
        return dict(version=1, startedAt=self.started_at, finishedAt=utc_now(),
                    elapsedMs=round((time.monotonic()-self.started)*1000, 3), segments=self.segments)


@contextmanager
def workspace(timings):
    directory = timings.call("workspace_create", tempfile.TemporaryDirectory, prefix="gamjaoj-")
    try:
        yield directory.name
    finally:
        timings.call("workspace_cleanup", directory.cleanup)
