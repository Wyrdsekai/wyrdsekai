# OpenClaw Gateway — containerized skill execution
# Provides sandboxed CLI skill execution via WebSocket bridge
#
# Build:
#   docker build -f docker/openclaw.Dockerfile -t wyrdsekai/openclaw-gateway .
#
# Run:
#   docker run -d \
#     --name openclaw-gateway \
#     -p 18789:18789 \
#     -e OPENCLAW_PORT=18789 \
#     wyrdsekai/openclaw-gateway

FROM ubuntu:24.04 AS base

# Install common dependencies for OpenClaw skills
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    wget \
    git \
    python3 \
    python3-pip \
    python3-venv \
    nodejs \
    npm \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for skill execution
RUN useradd -m -s /bin/bash openclaw

# Install common CLI tools used by OpenClaw skills
RUN pip3 install --break-system-packages \
    httpie \
    yt-dlp \
    && npm install -g \
    @anthropic-ai/sdk 2>/dev/null || true

# Gateway WebSocket server (Node.js)
WORKDIR /opt/openclaw

COPY docker/openclaw-gateway/ /opt/openclaw/

# Install gateway dependencies
RUN cd /opt/openclaw && npm install --production 2>/dev/null || true

# Skills directory — mount or copy SKILL.md files here
RUN mkdir -p /opt/openclaw/skills && chown -R openclaw:openclaw /opt/openclaw

# Security: No persistent credential storage
# Credentials are injected per-invocation as environment variables
# Container restarts = credentials gone
ENV OPENCLAW_PORT=18789
ENV OPENCLAW_MAX_EXEC_TIME=30000
ENV OPENCLAW_MAX_OUTPUT_SIZE=65536

# Health check
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:${OPENCLAW_PORT}/health || exit 1

# Run as non-root
USER openclaw

EXPOSE 18789

# Entrypoint: WebSocket gateway server
CMD ["node", "gateway.js"]
