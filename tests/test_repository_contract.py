import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "websocket_server" / "server_websocket.py"


class RepositoryContractTests(unittest.TestCase):
    def test_server_entrypoint_exists(self):
        self.assertTrue(SERVER.exists())

    def test_documented_routes_exist_in_server_source(self):
        source = SERVER.read_text(encoding="utf-8")
        for route in [
            '@app.websocket("/ws/{client_id}")',
            '@app.websocket("/dashboard/ws")',
            '@app.get("/dashboard")',
            '@app.get("/stats")',
            '@app.get("/recording/status")',
        ]:
            with self.subTest(route=route):
                self.assertIn(route, source)

    def test_documented_environment_variables_exist_in_server_source(self):
        source = SERVER.read_text(encoding="utf-8")
        for name in [
            "RESCUE360_DEMO_VIDEO_DIR",
            "RESCUE360_DASHBOARD_SLOTS",
            "RESCUE360_DASHBOARD_FRAME_INTERVAL_SEC",
            "RESCUE360_DASHBOARD_HISTORY_LIMIT",
            "RESCUE360_DEMO_TARGET_FPS",
        ]:
            with self.subTest(name=name):
                self.assertIn(name, source)

    def test_private_model_weights_are_ignored(self):
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("*.pt", gitignore)
        self.assertIn("*.engine", gitignore)

    def test_root_requirements_exists(self):
        self.assertTrue((ROOT / "requirements.txt").exists())


if __name__ == "__main__":
    unittest.main()
