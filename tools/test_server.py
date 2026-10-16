"""Minimal API for trying out the GE To API plugin: logs what the plugin sends.

Usage: python tools/test_server.py [--no-slots]
Listens on http://127.0.0.1:5000 (the plugin's default API URL):
  POST /transactions  -> 201
  PUT  /slots         -> 200, or 404 with --no-slots to see how the plugin handles a missing endpoint
"""
import json
import logging
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "127.0.0.1", 5000
SLOTS = "--no-slots" not in sys.argv

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("test_server")


class Handler(BaseHTTPRequestHandler):
    def _body(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
        try:
            return json.dumps(json.loads(body))
        except json.JSONDecodeError:
            return repr(body)

    def _reply(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        if self.path != "/transactions":
            return self._reply(404)
        log.info("Transaction: %s", self._body())
        self._reply(201)

    def do_PUT(self):
        if self.path != "/slots" or not SLOTS:
            return self._reply(404)
        log.info("Slots: %s", self._body())
        self._reply(200)

    def log_message(self, format, *args):
        # Silence the default per-request access log; bodies are logged above
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info("Listening on http://%s:%d (slots %s)", HOST, PORT, "enabled" if SLOTS else "disabled")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
