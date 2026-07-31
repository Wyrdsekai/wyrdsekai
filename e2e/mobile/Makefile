# Wyrdsekai Mobile E2E Testing
# Usage: make test-smoke | make test-quick | make test-full

SHELL := /bin/bash
export ANDROID_HOME ?= $(HOME)/Android/Sdk
export PATH := $(HOME)/.maestro/bin:$(ANDROID_HOME)/platform-tools:$(ANDROID_HOME)/emulator:$(PATH)

.PHONY: setup setup-maestro setup-avd boot-emulator \
        build-rn build-kmp build \
        server server-stop \
        test-smoke test-quick test-full \
        test-rn-smoke test-kmp-smoke \
        clean

# ── Setup ─────────────────────────────────────

setup: setup-maestro setup-avd
	@echo "Setup complete. Run: make boot-emulator && make server && make test-smoke"

setup-maestro:
	@./scripts/setup-maestro.sh

setup-avd:
	@./scripts/setup-avd.sh

boot-emulator:
	@./scripts/setup-avd.sh --boot

# ── Build ─────────────────────────────────────

build-rn:
	@./scripts/build-rn.sh

build-kmp:
	@./scripts/build-kmp.sh

build: build-rn build-kmp

# ── Server ────────────────────────────────────

server:
	@./scripts/start-test-server.sh

server-stop:
	@./scripts/start-test-server.sh stop

# ── Test Suites ───────────────────────────────

test-rn-smoke:
	@./scripts/run-suite.sh --platform rn --suite smoke

test-rn-quick:
	@./scripts/run-suite.sh --platform rn --suite quick

test-rn-full:
	@./scripts/run-suite.sh --platform rn --suite full

test-kmp-smoke:
	@./scripts/run-suite.sh --platform kmp --suite smoke

test-kmp-quick:
	@./scripts/run-suite.sh --platform kmp --suite quick

test-kmp-full:
	@./scripts/run-suite.sh --platform kmp --suite full

test-smoke:
	@./scripts/run-suite.sh --platform both --suite smoke

test-quick:
	@./scripts/run-suite.sh --platform both --suite quick

test-full:
	@./scripts/run-suite.sh --platform both --suite full

# ── Cleanup ───────────────────────────────────

clean:
	@rm -rf reports/ baselines/
	@./scripts/start-test-server.sh stop 2>/dev/null || true
	@echo "Cleaned reports and stopped test server"
