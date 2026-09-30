"""Jedi reads source as text; never import or execute the submitted program."""
import json
import sys
import jedi

jedi.settings.cache_directory = '/tmp/jedi-cache'
for line in sys.stdin:
    try:
        req = json.loads(line)
        code, offset = req['source'], req['offset']
        before = code.encode('utf-16-le')[:offset * 2].decode('utf-16-le')
        row = before.count('\n') + 1
        column = len(before.rsplit('\n', 1)[-1])
        script = jedi.Script(code, path='/tmp/Main.py', project=jedi.Project('/tmp', smart_sys_path=False))
        items = []
        for c in script.complete(row, column)[:200]:
            signatures = c.get_signatures()
            detail = signatures[0].to_string() if signatures else c.description
            kind = {'function': 3, 'class': 7, 'module': 9, 'param': 6, 'instance': 6}.get(c.type, 6)
            items.append({'label': c.name, 'kind': kind, 'detail': detail, 'insertText': c.name})
        print(json.dumps({'items': items}), flush=True)
    except Exception:
        print('{"items": []}', flush=True)
