import os

from app import app

if __name__ == "__main__":  # development only; production runs under gunicorn
    app.run(host=os.environ.get("APP_HOST", "0.0.0.0"), port=int(os.environ.get("APP_PORT", 6644)))
