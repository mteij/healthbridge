import gzip
import io
import logging
import os
import zlib

from dotenv import load_dotenv
from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

load_dotenv()


MAX_REQUEST_BYTES = 64 * 1024 * 1024


class GzipRequestMiddleware:
    """Accepts `Content-Encoding: gzip` request bodies (the app compresses its uploads)."""

    def __init__(self, wsgi_app):
        self.wsgi_app = wsgi_app

    def __call__(self, environ, start_response):
        if environ.get("HTTP_CONTENT_ENCODING", "").lower() == "gzip":
            try:
                length = int(environ.get("CONTENT_LENGTH") or 0)
                body = environ["wsgi.input"].read(length)
                decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
                data = decompressor.decompress(body, MAX_REQUEST_BYTES)
                if decompressor.unconsumed_tail:
                    raise ValueError("request body too large")
            except (ValueError, zlib.error):
                start_response("400 Bad Request", [("Content-Type", "application/json")])
                return [b'{"error": "invalid gzip body"}']
            environ["wsgi.input"] = io.BytesIO(data)
            environ["CONTENT_LENGTH"] = str(len(data))
            del environ["HTTP_CONTENT_ENCODING"]
        return self.wsgi_app(environ, start_response)


def create_app():
    logging.basicConfig(level=os.environ.get("HB_LOG_LEVEL", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 32 * 1024 * 1024
    CORS(app)
    app.wsgi_app = GzipRequestMiddleware(app.wsgi_app)

    @app.after_request
    def compress_response(response):
        """gzip JSON responses for clients that accept it (large for /fetch and /workouts)."""
        if (response.status_code == 200 and response.mimetype == "application/json"
                and not response.direct_passthrough and "gzip" in request.headers.get("Accept-Encoding", "")
                and "Content-Encoding" not in response.headers):
            data = response.get_data()
            if len(data) > 1024:
                response.set_data(gzip.compress(data, compresslevel=5))
                response.headers["Content-Encoding"] = "gzip"
                response.headers["Vary"] = "Accept-Encoding"
        return response

    from apiVersions.v2 import init_app as init_v2
    init_v2(app)

    from mcp_server import mcp
    app.register_blueprint(mcp)

    import cleanup
    cleanup.start_background_job()

    @app.get("/")
    def landing():
        return render_template("index.html", version=os.environ.get("HB_VERSION", "dev")), 200

    @app.get("/status")
    def status():
        import db
        from apiVersions.v2.auth import registration_state
        state = registration_state()
        return jsonify({
            "version": os.environ.get("HB_VERSION", "dev"),
            "registration": state["open"],
            "spots_left": state["spots_left"],
            "inactive_days": int(os.environ["HB_STALE_DAYS"]) if os.environ.get("HB_STALE_DAYS", "").isdigit() else None,
        }), 200

    @app.get("/healthz")
    def healthz():
        return jsonify({"status": "ok"}), 200

    return app


app = create_app()
