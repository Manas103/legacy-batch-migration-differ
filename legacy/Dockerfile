# Documented, not built in this environment (Docker daemon unavailable; see
# the root README). A plain Python 3.12 image is all this script needs.
FROM python:3.12-slim
WORKDIR /app
COPY legacy_batch.py layout.py generate_input.py ./
ENTRYPOINT ["python", "legacy_batch.py"]
