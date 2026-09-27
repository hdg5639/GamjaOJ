"""Host-wide exclusion shared with legacy serial Runners, plus a bounded number of functional slots."""
from contextlib import contextmanager, ExitStack
import fcntl
import os
from pathlib import Path
import time


def functional_slots():
    """Physical cap on concurrent FUNCTIONAL sandboxes on this host (GAMJAOJ_FUNCTIONAL_SLOTS, default 2)."""
    try:
        return max(1, min(16, int(os.environ.get("GAMJAOJ_FUNCTIONAL_SLOTS", "2"))))
    except ValueError:
        return 2


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
