import logging
import os

from dotenv import load_dotenv
from flask import Flask, jsonify
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

    @app.get("/healthz")
    def healthz():
        return jsonify({"status": "ok"}), 200

    return app


app = create_app()
