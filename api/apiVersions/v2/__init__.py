from blueprint import v2
from . import auth, records, workouts  # noqa: F401  (register routes)


def init_app(app):
    app.register_blueprint(v2)
