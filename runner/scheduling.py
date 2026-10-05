"""Host-wide exclusion shared with legacy serial Runners, plus a bounded number of functional slots."""
from contextlib import contextmanager, ExitStack
import fcntl
import os
from pathlib import Path
import time


def functional_slots():
    """Physical cap on concurrent FUNCTIONAL sandboxes on this host (GAMJAOJ_FUNCTIONAL_SLOTS, default 2)."""
    try:
        return max(1, min(32, int(os.environ.get("GAMJAOJ_FUNCTIONAL_SLOTS", "2"))))
    except ValueError:
        return 2


@contextmanager
def large_input_lock(directory=None):
    """Two host-wide large generated suites bound coordinator input buffers.

    Acquired outside measured helper/learner execution, underneath the existing
    functional/exclusive gate. Ordinary generated suites never take this lock.
    """
    directory = Path(directory or Path.home() / ".local/state/gamjaoj")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    slot = None
    while slot is None:
        for number in range(2):
            candidate = (directory / ("large-input-" + str(number) + ".lock")).open("a")
            try:
                fcntl.flock(candidate, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                candidate.close()
            else:
                slot = candidate
                break
        if slot is None:
            time.sleep(0.02)
    try:
        yield
    finally:
        slot.close()


@contextmanager
def execution_lock(mode, directory=None):
    if mode not in ("EXCLUSIVE", "FUNCTIONAL"):
        raise ValueError("Unknown execution mode")
    directory = Path(directory or Path.home() / ".local/state/gamjaoj")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with ExitStack() as stack:
        slot_number = None
        if mode == "FUNCTIONAL":
            while slot_number is None:
                for number in range(functional_slots()):
                    slot = (directory / ("functional-" + str(number) + ".lock")).open("a")
                    try:
                        fcntl.flock(slot, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        slot.close()
                    else:
                        stack.enter_context(slot)
                        slot_number = number
                        break
                if slot_number is None:
                    time.sleep(0.02)
        gate = stack.enter_context((directory / "runner.lock").open("a"))
        fcntl.flock(gate, fcntl.LOCK_SH if mode == "FUNCTIONAL" else fcntl.LOCK_EX)
        yield slot_number
