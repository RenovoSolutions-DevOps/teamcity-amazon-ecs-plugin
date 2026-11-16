#!/usr/bin/env bash
# Compiles and runs AwsConnectionCredentialsProviderTest (TestNG) against the classes built by build-awsconn.sh.
# Needs tools/testng/{testng-6.14.3,jcommander-1.72,bsh-2.0b6}.jar next to tools/kotlinc (see build-awsconn.sh).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/tmp/claude-1000/ecsplug}"
docker run --rm --user "$(id -u):$(id -g)" -v "$HERE":/src:ro -v "$WORK":/work eclipse-temurin:11-jdk bash -ec '
  export PATH=/work/tools/kotlinc/bin:$PATH
  PLUG=/work/build/plugin/server
  CP="$PLUG/kotlin-stdlib-1.4.31.jar:$PLUG/kotlin-stdlib-common-1.4.31.jar:/work/build/servlet-api.jar:/work/build/out/classes"
  for j in /work/srv/lib/*.jar; do
    case "$j" in */spring-*) CP="$CP:$j"; continue;; esac
    jar tf "$j" 2>/dev/null | grep -q "\.kotlin_module$" || CP="$CP:$j"
  done
  for j in $PLUG/aws-java-sdk-*.jar $PLUG/amazon-util-*.jar $PLUG/aws-ecs-common.jar $PLUG/kotlinx-coroutines-core-jvm-1.4.3.jar /work/tools/testng/*.jar; do CP="$CP:$j"; done
  rm -rf /tmp/t /work/build/testclasses && mkdir -p /tmp/t
  kotlinc -no-stdlib -Xskip-metadata-version-check -jvm-target 1.8 -cp "$CP" -d /tmp/t /src/aws-ecs-server/src/test/kotlin/jetbrains/buildServer/clouds/ecs/AwsConnectionCredentialsProviderTest.kt 2>&1 | grep -v "^warning\|^WARNING" || true
  # at run time the whole server classpath is fine (stdlib 1.4.31 stays first)
  RCP="$CP"; for j in /work/srv/lib/*.jar; do RCP="$RCP:$j"; done
  cp -r /tmp/t /work/build/testclasses
  echo "$RCP" > /work/build/test-runtime.cp
'
# run on the same Java as the production server (the server API classes are Java 21)
docker run --rm --user "$(id -u):$(id -g)" -v "$WORK":/work --entrypoint bash jetbrains/teamcity-server:2026.2.2 -ec '
  java -cp "/work/build/testclasses:$(cat /work/build/test-runtime.cp)" org.testng.TestNG -testclass jetbrains.buildServer.clouds.ecs.AwsConnectionCredentialsProviderTest 2>&1 | grep -v "^\s*at \|^WARNING" | tail -60
'
