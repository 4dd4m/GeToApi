"""Minimal test endpoint for the GE To API plugin.

Usage: python tools/test_server.py
Listens on http://127.0.0.1:5200/Api/add and logs every POSTed payload.
"""
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT, PATH = "127.0.0.1", 5200, "/Api/add"

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("test_server")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != PATH:
            self.send_error(404)
            return

        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
        try:
            log.info("Payload received: %s", json.dumps(json.loads(body)))
        except json.JSONDecodeError:
            log.warning("Non-JSON payload received: %r", body)

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        # Silence the default per-request access log; payloads are logged above
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log.info("Listening on http://%s:%d%s", HOST, PORT, PATH)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
