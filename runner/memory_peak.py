"""Observe kernel cgroup high-water marks on the controller, never sandbox output.

The last observed peak may precede a container's final allocation when its cgroup
is removed immediately at exit. Missing/unsupported counters stay unmeasured.
"""
import os
import re
import threading
from pathlib import Path


class MemoryPeak:
    def __init__(self, container_id, root=Path('/sys/fs/cgroup')):
        self.peak = None
        self.stop_event = threading.Event()
        self.thread = None
        self.paths = []
        self.counter = None
        if re.fullmatch('[0-9a-f]{64}', container_id):
            uid = os.getuid()
            groups = [root / 'system.slice' / f'docker-{container_id}.scope',
                      root / 'docker' / container_id,
                      root / 'user.slice' / f'user-{uid}.slice' / f'user@{uid}.service' / 'user.slice' / f'docker-{container_id}.scope',
                      root / 'memory' / 'docker' / container_id]
            self.paths = [group / counter for group in groups for counter in ('memory.peak', 'memory.max_usage_in_bytes')]

    def sample(self):
        for path in ([self.counter] if self.counter else self.paths):
            try:
                raw = path.read_text().strip()
                if raw.isdecimal() and 0 < int(raw) <= 2**63-1:
                    self.peak = max(self.peak or 0, int(raw))
                    self.counter = path
                    break
            except OSError:
                pass

    def observe(self):
        while not self.stop_event.is_set():
            self.sample()
            self.stop_event.wait(.002)

    def __enter__(self):
        if self.paths:
            self.thread = threading.Thread(target=self.observe, daemon=True)
            self.thread.start()
        return self

    def __exit__(self, *args):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=1)
        self.sample()
