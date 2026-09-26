"""Small local Engine API adapter for non-streaming sandbox control.

Create/start/attach still use Docker CLI. No retries or fallback after a mutation:
an uncertain response remains an infrastructure failure. Never expose this socket
to the sandbox. API v1.45: https://docs.docker.com/reference/api/engine/version/v1.45/
"""
import http.client
import json
import re
import socket


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__("localhost", timeout=30)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


class EngineControl:
    api = "1.45"
    name = re.compile(r"gamjaoj-sandbox-[0-9a-f]{32}")

    def __init__(self, endpoint):
        if not endpoint.startswith("unix:///"):
            raise ValueError("Runner requires a local Unix Docker endpoint")
        self.path = endpoint[len("unix://"):]
        version = json.loads(self.request("GET", "/version", (200,)))
        parts = lambda value: tuple(int(part) for part in value.split("."))
        if not parts(version["MinAPIVersion"]) <= parts(self.api) <= parts(version["ApiVersion"]):
            raise ValueError("Docker does not support the pinned control API")

    @classmethod
    def supports(cls, args):
        return ((len(args) == 4 and args[:3] == ("inspect", "--format", "{{json .State}}"))
                or (len(args) == 3 and args[:2] == ("rm", "--force"))
                or (len(args) == 2 and args[0] == "kill")) and bool(cls.name.fullmatch(args[-1]))

    def request(self, method, path, accepted):
        connection = UnixConnection(self.path)
        try:
            connection.request(method, path)
            response = connection.getresponse()
            body = response.read(1024 * 1024 + 1)
            if response.status not in accepted or len(body) > 1024 * 1024:
                raise OSError("Docker control request rejected or oversized")
            return body
        except http.client.HTTPException as exc:
            raise OSError("Incomplete Docker control response") from exc
        finally:
            connection.close()

    def command(self, args):
        if not self.supports(args):
            raise ValueError("Unsupported sandbox control command")
        path = "/v" + self.api + "/containers/" + args[-1]
        if args[0] == "inspect":
            container = json.loads(self.request("GET", path + "/json", (200,)))
            return json.dumps(container["State"]).encode()
        if args[0] == "kill":
            self.request("POST", path + "/kill", (204,))
        else:
            self.request("DELETE", path + "?force=true", (204,))
        return b""
