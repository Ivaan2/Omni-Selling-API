#!/bin/sh
# Codex-specific installation, preserving source, existing .env and local database state.
set -eu
cd /workspace/Omni-Selling-API
mkdir -p /workspace/tools
if [ ! -x /workspace/tools/jdk-21/bin/javac ] && { ! command -v javac >/dev/null 2>&1 || [ ! -f "${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/lib/ct.sym" ]; }; then
  docker pull eclipse-temurin:21-jdk
  source_container=$(docker create eclipse-temurin:21-jdk)
  trap 'docker rm "$source_container" >/dev/null 2>&1 || true' EXIT
  docker cp "$source_container:/opt/java/openjdk" /workspace/tools/jdk-21
  docker rm "$source_container" >/dev/null
  trap - EXIT
fi
. ./scripts/cloud-env.sh
./scripts/local-env.sh
# Dependencies and real PostgreSQL tests; no running services are assumed to survive a snapshot.
./mvnw -B clean verify
docker pull postgres:17.6
docker pull python:3.13-slim
