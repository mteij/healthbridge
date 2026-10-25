import logging
import os

from dotenv import load_dotenv
from flask import Flask, jsonify, render_template
from flask_cors import CORS

load_dotenv()


def create_app():
    logging.basicConfig(level=os.environ.get("HB_LOG_LEVEL", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 32 * 1024 * 1024
    CORS(app)

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
