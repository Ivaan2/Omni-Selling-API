#!/bin/sh
# Optional cloud-only Docker validation; ordinary networks can use docker build directly.
set -eu
cd /workspace/Omni-Selling-API
. ./scripts/cloud-env.sh
mkdir -p /workspace/.docker-cli
export DOCKER_CONFIG=/workspace/.docker-cli
python3 - <<'PY'
import os,socket,subprocess,urllib.parse
args=['docker','build']
proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
if proxy.hostname:
 address=socket.getaddrinfo(proxy.hostname,proxy.port or 80,socket.AF_INET)[0][4][0]
 args+=['--add-host',proxy.hostname+':'+address]
args+=['--secret','id=maven-settings,src=/workspace/.orderms-support/maven-settings.xml']
if os.path.isfile('/etc/ssl/certs/java/cacerts'):
 args+=['--secret','id=java-truststore,src=/etc/ssl/certs/java/cacerts']
args+=['-t','order-management:local','.']
subprocess.run(args,check=True)
PY
