"""Transport fixture only; actual account/administrator contracts are tested in Spring tests."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
class Handler(BaseHTTPRequestHandler):
    def respond(self):
        body = self.rfile.read(int(self.headers.get('Content-Length',0)))
        payload = json.dumps(dict(host=self.headers.get('Host'), cookie=self.headers.get('Cookie'),
            method=self.command, path=self.path, body=body.decode())).encode()
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.send_header('Set-Cookie','GAMJAOJ_SESSION=fixture; HttpOnly; SameSite=Lax')
        self.send_header('Content-Length',str(len(payload)))
        self.end_headers(); self.wfile.write(payload)
    do_GET = respond
    do_POST = respond
    def log_message(self,*args): pass
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
