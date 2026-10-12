#!/bin/sh
# Source this only inside the Codex /workspace cloud machine.
if [ -x /workspace/tools/jdk-21/bin/javac ]; then
  export JAVA_HOME=/workspace/tools/jdk-21
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export MAVEN_USER_HOME=/workspace/.m2
mkdir -p /workspace/.orderms-support /workspace/.m2/repository
python3 - <<'PY'
import os,pathlib,urllib.parse,xml.sax.saxutils
proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
settings=pathlib.Path('/workspace/.orderms-support/maven-settings.xml')
if proxy.hostname:
 host=xml.sax.saxutils.escape(proxy.hostname)
 settings.write_text(f'<settings><proxies><proxy><id>cloud</id><active>true</active><protocol>http</protocol><host>{host}</host><port>{proxy.port or 80}</port><nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy></proxies></settings>')
else:
 settings.write_text('<settings/>')
PY
export MAVEN_ARGS="-s /workspace/.orderms-support/maven-settings.xml -Dmaven.repo.local=/workspace/.m2/repository"
# Use the machine's supported CA store; never disable TLS verification.
if [ -r /etc/ssl/certs/java/cacerts ]; then
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
