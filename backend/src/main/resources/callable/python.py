import json as _json, sys as _sys

def _integer(s):
    v = int(s)
    if not -(1 << 63) <= v < (1 << 63): raise ValueError('long range')
    return v

def _reject(s): raise ValueError('integer required')

def _checked(v, t):
    if t.endswith('[]'):
        if type(v) is not list: raise ValueError('array required')
        return [_checked(x, t[:-2]) for x in v]
    if t == 'String':
        if type(v) is not str: raise ValueError('string required')
    elif t == 'boolean':
        if type(v) is not bool: raise ValueError('boolean required')
    else:
        bits = 32 if t == 'int' else 64
        if type(v) is not int or not -(1 << (bits-1)) <= v < (1 << (bits-1)): raise ValueError('integer range')
    return v

def _wire(v):
    if type(v) is str:
        out = '"'
        for c in v:
            n = ord(c)
            if n <= 32 or 0xd800 <= n <= 0xdfff: out += '\\u%04x' % n
            elif n > 0xffff:
                n -= 0x10000
                out += '\\u%04x\\u%04x' % (0xd800+(n>>10), 0xdc00+(n&1023))
            elif c in ('"', '\\'): out += '\\' + c
            else: out += c
        return out + '"'
    if type(v) is list: return '[' + ','.join(_wire(x) for x in v) + ']'
    if type(v) is bool: return 'true' if v else 'false'
    return str(v)

def _run():
    data = _sys.stdin.buffer.read(6291457)
    if len(data) > 6291456: raise ValueError('input too large')
    cases = _json.loads(data.decode('utf-8'), parse_int=_integer, parse_float=_reject, parse_constant=_reject)
    if type(cases) is not list or not 1 <= len(cases) <= 100: raise ValueError('case count')
    for calls in cases:
        if type(calls) is not list or not 1 <= len(calls) <= 1000000: raise ValueError('call count')
        if _single and len(calls) != 1: raise ValueError('single call required')
        user = UserSolution()
        for index, call in enumerate(calls):
            if type(call) is not list or not call or type(call[0]) is not str: raise ValueError('method')
            name = call[0]
            if not _single and index == 0 and name != 'init': raise ValueError('init required')
            if name not in _methods: raise ValueError('unknown method')
            target, returns, types = _methods[name]
            if len(call) != len(types)+1: raise ValueError('arity')
            args = [_checked(v,t) for v,t in zip(call[1:],types)]
            result = getattr(user, target)(*args)
            if returns != 'void': print(_wire(_checked(result,returns)))
_run()
