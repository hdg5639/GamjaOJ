"""Controller-side CPU accounting for a live cgroup v2 execution sandbox."""
from pathlib import Path


class CpuUsage:
    def __init__(self, pid, root=Path('/sys/fs/cgroup'), proc=Path('/proc')):
        lines=(proc / str(int(pid)) / 'cgroup').read_text().splitlines()
        group=next(line[3:] for line in lines if line.startswith('0::'))
        self.path=(root / group.lstrip('/') / 'cpu.stat').resolve()
        if not self.path.is_relative_to(root.resolve()):
            raise ValueError('Invalid controller cgroup path')
        self.baseline=self.read()
        self.oom_baseline=self.oom_count()

    def read(self):
        values=dict(line.split() for line in self.path.read_text().splitlines())
        value=int(values['usage_usec'])
        if value<0:raise ValueError('Invalid CPU usage counter')
        return value

    def milliseconds(self):
        value=self.read()-self.baseline
        if value<0:raise ValueError('CPU usage counter moved backwards')
        return value/1000

    def oom_count(self):
        path=self.path.parent/'memory.events'
        values=dict(line.split() for line in path.read_text().splitlines())
        value=int(values['oom_kill'])
        if value<0:raise ValueError('Invalid OOM counter')
        return value

    def oom_killed(self):
        return self.oom_count()>self.oom_baseline
