"""Private, bounded completion service. Only fixed language-analysis commands are accepted.

At most one process per language. A process/workspace is destroyed when its owner
changes or after two idle minutes; neither source nor symbols cross account boundaries.
The service has no credentials, host mounts, public port, or outbound network.
"""
import glob
import http.server
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import tempfile
import threading
import time

LIMIT = 65536
TIMEOUT = 25


class Engine:
    def __init__(self, language, owner):
        self.language, self.owner = language, owner
        self.directory = tempfile.mkdtemp(prefix='completion-')
        self.root = Path(self.directory)
        self.inbox = queue.Queue(maxsize=256)
        self.write_lock = threading.Lock()
        self.sequence = 0
        self.version = 0
        self.opened = False
        self.idle = threading.Event()
        self.last_used = time.monotonic()
        self.settings = {'java': {
            'configuration': {'runtimes': [{'name': 'JavaSE-1.8', 'path': '/opt/java8', 'default': True}]},
            'import': {'gradle': {'enabled': False}, 'maven': {'enabled': False}},
            'autobuild': {'enabled': False},
            'completion': {'favoriteStaticMembers': [], 'maxResults': 200},
            'signatureHelp': {'enabled': False},
        }}
        if language == 'JAVA':
            self.project = self.root / 'project'
            self.project.mkdir()
            (self.project / 'src').mkdir()
            (self.project / '.project').write_text('<projectDescription><name>Completion</name><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>')
            (self.project / '.classpath').write_text('<classpath><classpathentry kind="src" path="src"/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-1.8"/><classpathentry kind="output" path="bin"/></classpath>')
            self.path = self.project / 'src' / 'Main.java'
            shutil.copytree('/opt/jdtls/config_linux', self.root / 'config')
            launcher = glob.glob('/opt/jdtls/plugins/org.eclipse.equinox.launcher_*.jar')[0]
            command = ['/opt/java21/bin/java', '-Xms64m', '-Xmx448m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
                       '-Declipse.application=org.eclipse.jdt.ls.core.id1', '-Declipse.product=org.eclipse.jdt.ls.core.product',
                       '-Dosgi.bundles.defaultStartLevel=4', '-Djava.import.generatesMetadataFilesAtProjectRoot=false',
                       '-jar', launcher, '-configuration', str(self.root / 'config'), '-data', str(self.root / 'workspace')]
        elif language == 'CPP':
            self.project = self.root
            self.path = self.root / 'Main.cpp'
            command = ['clangd', '--background-index=0', '--clang-tidy=0', '--header-insertion=never',
                       '--completion-style=detailed', '--limit-results=200', '-j=1', '--log=error']
        else:
            self.project = self.root
            command = ['python', '-I', '/service/python_worker.py']
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, cwd=self.directory, start_new_session=True)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            while True:
                if self.language == 'PYTHON':
                    line = self.process.stdout.readline()
                    if not line:
                        break
                    self.inbox.put(json.loads(line), timeout=1)
                    continue
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        raise EOFError()
                    if line in (b'\r\n', b'\n'):
                        break
                    key, _, value = line.decode().partition(':')
                    headers[key.lower()] = value.strip()
                size = int(headers.get('content-length', '0'))
                if not 0 < size <= 4 * 1024 * 1024:
                    raise ValueError('Invalid LSP frame')
                message = json.loads(self.process.stdout.read(size))
                if message.get('method') == 'textDocument/clangd.fileStatus':
                    if message.get('params', {}).get('state') == 'idle':
                        self.idle.set()
                if 'method' in message and 'id' in message:
                    # No server-initiated commands, edits, dynamic execution or network requests.
                    result = [self.settings.get('java', {}) for _ in message.get('params', {}).get('items', [])] if message['method'] == 'workspace/configuration' else None
                    self.send({'jsonrpc': '2.0', 'id': message['id'], 'result': result})
                elif 'id' in message:
                    self.inbox.put(message, timeout=1)
        except Exception:
            pass
        finally:
            try:
                self.inbox.put_nowait({'error': 'closed'})
            except queue.Full:
                pass

    def send(self, message):
        data = json.dumps(message).encode()
        with self.write_lock:
            if self.language == 'PYTHON':
                self.process.stdin.write(data + b'\n')
            else:
                self.process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
            self.process.stdin.flush()

    def notify(self, method, params):
        self.send({'jsonrpc': '2.0', 'method': method, 'params': params})

    def request(self, method, params, deadline):
        self.sequence += 1
        request_id = self.sequence
        self.send({'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params})
        while True:
            item = self.inbox.get(timeout=max(.01, deadline - time.monotonic()))
            if 'error' in item:
                raise RuntimeError('Language analysis failed')
            if item.get('id') == request_id:
                return item.get('result')

    def complete(self, source, offset):
        deadline = time.monotonic() + TIMEOUT
        if self.language == 'PYTHON':
            self.send({'source': source, 'offset': offset})
            return self.inbox.get(timeout=TIMEOUT)
        uri = self.path.as_uri()
        self.idle.clear()
        if not self.opened:
            self.path.write_text(source)
            self.request('initialize', {
                'processId': None, 'rootUri': self.project.as_uri(),
                'workspaceFolders': [{'uri': self.project.as_uri(), 'name': 'Completion'}],
                'capabilities': {'textDocument': {'completion': {'completionItem': {'snippetSupport': False}}},
                                 'general': {'positionEncodings': ['utf-16']}},
                'initializationOptions': {'settings': self.settings, 'fallbackFlags': ['-std=c++17'], 'clangdFileStatus': True,
                                          'extendedClientCapabilities': {'classFileContentsSupport': False}}
            }, deadline)
            self.notify('initialized', {})
            self.notify('workspace/didChangeConfiguration', {'settings': self.settings})
            self.version += 1
            self.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'java' if self.language == 'JAVA' else 'cpp', 'version': self.version, 'text': source}})
            self.opened = True
        else:
            self.version += 1
            self.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': self.version}, 'contentChanges': [{'text': source}]})
        if self.language == 'CPP':
            self.idle.wait(timeout=max(.01, deadline - time.monotonic()))
        before = source.encode('utf-16-le')[:offset * 2].decode('utf-16-le')
        position = {'line': before.count('\n'), 'character': len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2}
        result = self.request('textDocument/completion', {'textDocument': {'uri': uri}, 'position': position, 'context': {'triggerKind': 1}}, deadline)
        items = result if isinstance(result, list) else (result or {}).get('items', [])
        # Only inert completion text is forwarded; never commands, paths or server metadata.
        allowed = ('label', 'kind', 'detail', 'insertText', 'insertTextFormat', 'textEdit', 'additionalTextEdits', 'sortText', 'filterText')
        return {'items': [{k: item[k] for k in allowed if k in item} for item in items[:200]],
                'isIncomplete': (result or {}).get('isIncomplete', False) if isinstance(result, dict) else False}

    def close(self):
        try:
            os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.process.wait(timeout=5)
        self.process.stdin.close()
        self.process.stdout.close()
        shutil.rmtree(self.directory, ignore_errors=True)


locks = {language: threading.Lock() for language in ('JAVA', 'CPP', 'PYTHON')}
engines = {}


def complete(language, owner, source, offset):
    lock = locks[language]
    if not lock.acquire(blocking=False):
        return {'items': [], 'unavailable': True}
    try:
        engine = engines.get(language)
        if engine and engine.owner != owner:
            engines.pop(language).close()
            engine = None
        if not engine:
            engine = Engine(language, owner)
            engines[language] = engine
        engine.last_used = time.monotonic()
        return engine.complete(source, offset)
    except Exception:
        engine = engines.pop(language, None)
        if engine:
            engine.close()
        return {'items': [], 'unavailable': True}
    finally:
        lock.release()


def reap():
    while True:
        time.sleep(30)
        for language, lock in locks.items():
            if lock.acquire(blocking=False):
                try:
                    if language in engines and time.monotonic() - engines[language].last_used > 120:
                        engines.pop(language).close()
                finally:
                    lock.release()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Do not log submitted source or account identifiers.

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self.reply(200 if self.path == '/healthz' else 404, {})

    def do_POST(self):
        if self.path != '/complete':
            return self.reply(404, {})
        try:
            self.connection.settimeout(5)
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= LIMIT * 6 + 1024:
                raise ValueError()
            request = json.loads(self.rfile.read(size))
            language, owner, source, offset = (request[k] for k in ('language', 'owner', 'source', 'offset'))
            if language not in locks or not isinstance(owner, str) or not 1 <= len(owner) <= 128:
                raise ValueError()
            if not isinstance(source, str) or len(source.encode()) > LIMIT or type(offset) is not int or not 0 <= offset <= len(source.encode('utf-16-le')) // 2:
                raise ValueError()
            source.encode('utf-16-le')[:offset * 2].decode('utf-16-le')
        except (ValueError, KeyError, TypeError, UnicodeError):
            return self.reply(400, {})
        self.reply(200, complete(language, owner, source, offset))


if __name__ == '__main__':
    threading.Thread(target=reap, daemon=True).start()
    http.server.ThreadingHTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
