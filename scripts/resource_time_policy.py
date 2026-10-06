"""Learner-facing time headroom. Memory and Runner admission are unchanged."""
import copy
import math

MINIMUM_SECONDS = {'CPP': 3, 'JAVA': 5, 'PYTHON': 8}
CEILING_SECONDS = 180
POLICY = 'LEARNER_FRIENDLY_V1'


def seconds(language, value):
    if language not in MINIMUM_SECONDS or type(value) not in (int, float) or not math.isfinite(value) or value <= 0 or value > CEILING_SECONDS:
        raise ValueError('invalid bounded language time')
    return value


def relaxed_limits(limits):
    """Widen existing published budgets monotonically; never change memory."""
    if limits is None:
        return None  # Already uses the default 3/5/8-second Runner profiles.
    result = copy.deepcopy(limits)
    for language, floor in MINIMUM_SECONDS.items():
        current = seconds(language, limits[language])
        result[language] = round(min(CEILING_SECONDS, max(floor, current * 2)), 3)
    if result != limits:
        result['analysis'] = (limits.get('analysis', '')[:5400] +
            ' Learner-friendly V1: existing times doubled with C++/Java/Python floors '
            '3/5/8 seconds, capped at 180 seconds; memory unchanged. '
            'This monotonic relaxation is not a new maximum-input qualification.')
    return result


def measured_seconds(language, maximum_ms, *, algorithm_sensitive=False):
    if type(maximum_ms) not in (int, float) or not math.isfinite(maximum_ms) or maximum_ms <= 0:
        raise ValueError('positive finite timing evidence required')
    multiplier, startup_ms = (2, 500) if algorithm_sensitive else (3, 1000)
    result = max(MINIMUM_SECONDS[language], math.ceil((maximum_ms * multiplier + startup_ms) / 250) * .25)
    if result > CEILING_SECONDS:
        raise ValueError('learner headroom exceeds bounded capacity; review required')
    return result
