# Wyrdsekai Relay — single-container ("all-in-one") image.
#
# Collapses the 4-service docker-compose stack (nats + registration + caddy +
# certinit) into ONE container that runs the same three long-lived processes
# plus a first-boot cert-gen step — the SAME process set the `--native` mode
# already runs. This is the `relay.sh --docker` path; `--native` is unchanged.
#
# Why one container fixes the class of bug that bit relay-node 2026-06-19:
#   - No custom nats entrypoint writing a runtime config into /tmp (reboot-
#     volatile) and `include`-ing the real conf by a path that NATS resolves
#     relative to /tmp → /tmp/etc/nats/relay.conf → crash-loop. Here nats reads
#     the persistent conf directly and the monitor stays on 127.0.0.1:8222
#     (the reaper is in-container), so no override conf exists at all.
#   - All mutable state (certs, data, the live relay.conf) lives in mounted
#     volumes, never /tmp.
#
# Binaries are lifted verbatim from the official images so they match exactly
# what the compose stack ran.

FROM nats:2.10-alpine  AS natsbin
FROM caddy:2-alpine    AS caddybin

FROM python:3.12-alpine

# openssl+bash for gen-cert.sh; curl for healthchecks; tini as PID 1 reaper;
# openssh for the OPTIONAL forwarding-only tunnel sshd ( —
# only started when WYRD_SSH_TUNNEL_ENABLED=true; dormant otherwise).
RUN apk add --no-cache openssl bash curl tini openssh \
 && pip install --no-cache-dir nkeys nats-py pynacl

# Unprivileged, shell-less account the reverse tunnels authenticate as. Per-zone
# isolation is per-KEY (permitlisten), not per-user — one account is enough.
# Two non-obvious sshd requirements this satisfies:
#  1. `adduser -D` leaves the shadow password "!" (LOCKED); sshd refuses a locked
#     account even for pubkey auth ("account is locked → invalid user"). Flip
#     "!"→"*" so the account is valid-but-passwordless (key auth only).
#  2. With `StrictModes yes` (the default) sshd validates the account's HOME dir
#     and SILENTLY refuses the key if home is missing — so we must NOT use -H; let
#     adduser create /home/wyrd-tunnel (owned by the user, 0755). nologin lives at
#     /sbin/nologin on Alpine.
RUN adduser -D -s /sbin/nologin wyrd-tunnel \
 && sed -i 's/^wyrd-tunnel:!/wyrd-tunnel:*/' /etc/shadow

# nats-server lives at /usr/local/bin in the official 2.10-alpine image; the
# wildcard COPY tolerates either /nats-server or /usr/local/bin/nats-server.
COPY --from=natsbin  /usr/local/bin/nats-server  /usr/local/bin/nats-server
COPY --from=caddybin /usr/bin/caddy              /usr/bin/caddy

# Relay assets (baked; the live, writable copies are seeded into the data
# volume on first boot by the entrypoint).
COPY relay.conf            /opt/relay/relay.conf.seed
COPY Caddyfile             /opt/relay/Caddyfile
# registration.py lives at /app to match relay.sh's op exec path
# (REG_PY=/app/registration.py) so `relay.sh invite/list/remove` work verbatim
# against this container via `docker exec`.
COPY registration.py       /app/registration.py
COPY certinit/gen-cert.sh  /usr/local/bin/gen-cert.sh
COPY aio-entrypoint.sh     /usr/local/bin/aio-entrypoint
# The forwarding-only tunnel sshd config (the entrypoint copies it into the data
# volume, seds in the topology, and only launches sshd when the tunnel is on).
COPY tunnel-sshd_config    /opt/relay/tunnel-sshd_config.seed
RUN chmod +x /usr/local/bin/gen-cert.sh /usr/local/bin/aio-entrypoint

# Persistent state — NEVER /tmp.
VOLUME ["/certs", "/var/lib/wyrd-relay"]

# Public TLS front (caddy) + native NATS zone leg. The tunnel sshd control port
# (2222) and the per-zone public range are published by `relay.sh -p`, not here.
EXPOSE 4443 4222 2222

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/aio-entrypoint"]
