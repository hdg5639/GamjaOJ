import json
import os
import unittest
from unittest.mock import patch

from runner.docker_control import EngineControl
from runner.judge import docker, engine_control, InfrastructureError


class EngineControlTests(unittest.TestCase):
    name = "gamjaoj-sandbox-" + "a" * 32

    def setUp(self):
        engine_control.cache_clear()
        self.addCleanup(engine_control.cache_clear)

    def control(self):
        with patch.object(EngineControl, "request", return_value=b'{"MinAPIVersion":"1.44","ApiVersion":"1.56"}'):
            return EngineControl("unix:///var/run/docker.sock")

    def test_exact_container_only_and_state_contract(self):
        control = self.control()
        state = {"Running": False, "ExitCode": 137, "OOMKilled": True, "Error": "",
                 "StartedAt": "start", "FinishedAt": "finish"}
        with patch.object(control, "request", return_value=json.dumps({"State": state}).encode()) as request:
            result = control.command(("inspect", "--format", "{{json .State}}", self.name))
            self.assertEqual(state, json.loads(result))
            request.assert_called_once_with("GET", "/v1.45/containers/" + self.name + "/json", (200,))
        for name in ("other-service", self.name + "/../other", "gamjaoj-sandbox-", "a" * 64):
            self.assertFalse(control.supports(("rm", "--force", name)))
            with self.assertRaises(ValueError):
                control.command(("rm", "--force", name))

    def test_mutations_are_single_requests_and_failure_is_not_retried(self):
        control = self.control()
        for args, method, suffix in [(("kill", self.name), "POST", "/kill"),
                                     (("rm", "--force", self.name), "DELETE", "?force=true")]:
            with patch.object(control, "request", return_value=b"") as request:
                self.assertEqual(b"", control.command(args))
                request.assert_called_once_with(method, "/v1.45/containers/" + self.name + suffix, (204,))
            with patch.object(control, "request", side_effect=OSError("response lost")) as request:
                with self.assertRaises(OSError):
                    control.command(args)
                self.assertEqual(1, request.call_count)

    def test_remote_or_incompatible_engine_is_rejected(self):
        with self.assertRaises(ValueError):
            EngineControl("tcp://remote:2375")
        with patch.object(EngineControl, "request", return_value=b'{"MinAPIVersion":"1.46","ApiVersion":"1.56"}'):
            with self.assertRaises(ValueError):
                EngineControl("unix:///var/run/docker.sock")

    def test_api_failure_does_not_fall_back_to_cli(self):
        with patch.dict(os.environ, {"GAMJAOJ_DOCKER_CONTROL": "engine"}), \
                patch("runner.judge.engine_control") as engine, patch("runner.judge.docker_cli") as cli:
            engine.return_value.command.side_effect = OSError("response lost")
            with self.assertRaises(InfrastructureError):
                docker("rm", "--force", self.name)
            cli.assert_not_called()

    def test_http_failures_close_connection_without_retry(self):
        control = self.control()
        for status, body in [(404, b'{}'), (500, b'{}'), (200, b'x' * (1024 * 1024 + 1))]:
            with patch('runner.docker_control.UnixConnection') as connection:
                response = connection.return_value.getresponse.return_value
                response.status = status
                response.read.return_value = body
                with self.assertRaises(OSError):
                    control.request('GET', '/v1.45/containers/' + self.name + '/json', (200,))
                connection.return_value.request.assert_called_once()
                connection.return_value.close.assert_called_once()

    def test_cli_context_and_host_override_resolve_same_endpoint(self):
        for env, endpoint in [({"DOCKER_HOST": "unix:///custom.sock"}, "unix:///custom.sock"),
                              ({"DOCKER_CONTEXT": "selected", "DOCKER_HOST": "tcp://ignored:2375"}, "unix:///context.sock")]:
            engine_control.cache_clear()
            with patch.dict(os.environ, env, clear=True), \
                    patch("runner.judge.docker_cli", return_value=b"unix:///context.sock\n"), \
                    patch("runner.judge.EngineControl") as control:
                engine_control()
                control.assert_called_once_with(endpoint)
