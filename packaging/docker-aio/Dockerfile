# Wyrdsekai all-in-one image — one container, whole household node:
# JVM server + llama.cpp (drive/voice) + nats + bundled runtime assets.
# Models are NOT baked in; the entrypoint pulls them into the /data
# volume on first boot (interrupt-safe), tiered by GPU detection.
#
# Variants via LLAMA_BASE (build context = repo root):
#   CPU:  --build-arg LLAMA_BASE=ghcr.io/ggml-org/llama.cpp:server
#   CUDA: --build-arg LLAMA_BASE=ghcr.io/ggml-org/llama.cpp:server-cuda
#         (run with --gpus all)
# Basing on the llama.cpp image keeps the inference runtime identical to
# the compose setup — binary, libs and (for CUDA) the runtime all match.
ARG LLAMA_BASE=ghcr.io/ggml-org/llama.cpp:server
FROM ${LLAMA_BASE}

ARG NATS_VERSION=2.10.22
ARG DIST_DIR=build/dist/wyrdsekai-aio
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg python3 python3-venv jq \
    && wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && . /etc/os-release \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $VERSION_CODENAME main" \
        > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update && apt-get install -y --no-install-recommends temurin-25-jre \
    && rm -rf /var/lib/apt/lists/*

RUN wget -qO /tmp/nats.tgz "https://github.com/nats-io/nats-server/releases/download/v${NATS_VERSION}/nats-server-v${NATS_VERSION}-linux-amd64.tar.gz" \
    && tar -xzf /tmp/nats.tgz -C /tmp \
    && mv /tmp/nats-server-*/nats-server /usr/local/bin/nats-server \
    && rm -rf /tmp/nats*

COPY ${DIST_DIR}/ /opt/wyrdsekai/
COPY packaging/docker-aio/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /opt/wyrdsekai/bin/* 2>/dev/null || true

# WYRDSEKAI_DATA_DIR is the key SystemPaths.dataDir() actually reads (2026-07-18):
# the old WYRDSEKAI_DATA had ZERO readers in the Java, so world.db/souls/identity
# landed in the container writable layer ($HOME/.wyrdsekai) and were destroyed on
# recreate, while models downloaded into /data went unfound. WYRDSEKAI_DATA kept as
# a back-compat alias for the entrypoint's shell logic.
ENV WYRDSEKAI_DATA=/data \
    WYRDSEKAI_DATA_DIR=/data \
    WYRDSEKAI_HOME=/opt/wyrdsekai \
    WYRDSEKAI_LIBRARY_STARTER=true

VOLUME /data
# 7070 HTTP/WS + /app  7022 ssh  8200 drive  8201 voice  4222 nats
EXPOSE 7070 7022 8200 8201

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s \
    CMD curl -sf http://127.0.0.1:7070/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
