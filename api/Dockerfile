FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN useradd --system --uid 10001 healthbridge
USER healthbridge

EXPOSE 6644
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:6644/healthz', timeout=3)"

# one process, several threads: the login rate limiter keeps its state in memory
CMD ["gunicorn", "--bind", "0.0.0.0:6644", "--workers", "1", "--threads", "8", "--access-logfile", "-", "app:app"]
