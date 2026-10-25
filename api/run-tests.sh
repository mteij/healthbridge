#!/bin/sh
# Runs the test suite against a throwaway MongoDB (same major version as production).
set -e
cd "$(dirname "$0")"
net=hb-test-net
docker network inspect "$net" >/dev/null 2>&1 || docker network create "$net" >/dev/null
docker run -d --rm --name hb-test-mongo --network "$net" mongo:9 >/dev/null
trap 'docker stop hb-test-mongo >/dev/null 2>&1' EXIT
docker run --rm --network "$net" -v "$PWD:/app" -w /app -e TEST_MONGO_URI="mongodb://hb-test-mongo:27017" \
  python:3.13-slim sh -c 'pip install -q --root-user-action=ignore -r requirements.txt -r requirements-dev.txt && python -m pytest -q -p no:cacheprovider "$@"' sh "$@"
