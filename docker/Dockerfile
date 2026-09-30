# Wyrdsekai Server — Runtime image
#
# Pre-requisite: ./gradlew :server:installDist
#
# Usage:
#   docker build -t wyrdsekai:latest -f docker/Dockerfile .
#   docker run -p 127.0.0.1:7070:7070 -p 7443:7443 wyrdsekai:latest
#   (plain 7070 for the browser on this machine only; 7443 is the encrypted door for the network)

FROM eclipse-temurin:25-jre

# The plain web port listens inside the container so the host can reach it; publish it on the
# host's loopback only. Every /api call still needs a login.
ENV WYRDSEKAI_HTTP_BIND=0.0.0.0

LABEL org.opencontainers.image.title="Wyrdsekai"
LABEL org.opencontainers.image.description="Distributed text-native OS built on MUD paradigm"

RUN groupadd -r wyrd && useradd -r -g wyrd -m -d /home/wyrd wyrd

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY server/build/install/server/ ./
COPY scripts/ /app/scripts/

RUN mkdir -p /data && chown -R wyrd:wyrd /app /data

USER wyrd

ENV WYRDSEKAI_DATA_DIR=/data \
    WYRDSEKAI_SCRIPTS_DIR=/app/scripts \
    JAVA_OPTS="-Xms256m -Xmx1g --enable-native-access=ALL-UNNAMED"

EXPOSE 7070 7071 7443

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -sf http://localhost:7070/health || exit 1

ENTRYPOINT ["bin/server"]
