#!/usr/bin/env bash
# Builds aws-ecs.zip (server plugin only) without Gradle.
#
# The upstream Gradle build needs a private "amazon-util 2021.1-SNAPSHOT" artifact, so instead this compiles the
# server module with Kotlin 1.4.31 (the version upstream uses) against the jars of a real TeamCity server and
# repackages it into the layout of the existing, already installed plugin zip.
#
# Inputs (all under $WORK, default /tmp/claude-1000/ecsplug):
#   srv/lib/*.jar          WEB-INF/lib of jetbrains/teamcity-server:<version>   (server API at compile time)
#   build/servlet-api.jar  /opt/teamcity/lib/servlet-api.jar of that image
#   build/plugin/          extracted currently installed aws-ecs.zip           (SDK jars, amazon-util, agent part)
#   tools/kotlinc/         kotlin-compiler-1.4.31
# Output: $WORK/build/out/aws-ecs.zip
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/tmp/claude-1000/ecsplug}"
OUT="$WORK/build/out"
rm -rf "$OUT"; mkdir -p "$OUT"

docker run --rm --user "$(id -u):$(id -g)" \
  -v "$HERE":/src:ro -v "$WORK":/work -v "$OUT":/out \
  eclipse-temurin:11-jdk bash -ec '
    export PATH=/work/tools/kotlinc/bin:$PATH
    PLUG=/work/build/plugin/server
    CP="$PLUG/kotlin-stdlib-1.4.31.jar:$PLUG/kotlin-stdlib-common-1.4.31.jar:/work/build/servlet-api.jar"
    # server libs ship Kotlin 1.9 jars; the plugin must compile against (and run with) its own 1.4.31 stdlib
    # skip server jars compiled with newer Kotlin: kotlinc 1.4 cannot read their metadata and none is needed here
    for j in /work/srv/lib/*.jar; do
      case "$j" in */spring-*) CP="$CP:$j"; continue;; esac   # Spring 6 is Kotlin-compiled but its metadata is readable
      jar tf "$j" 2>/dev/null | grep -q "\.kotlin_module$" || CP="$CP:$j"
    done
    for j in $PLUG/aws-java-sdk-ecs-*.jar $PLUG/aws-java-sdk-core-*.jar \
             $PLUG/aws-java-sdk-cloudwatch-*.jar $PLUG/amazon-util-*.jar $PLUG/aws-ecs-common.jar \
             $PLUG/kotlinx-coroutines-core-jvm-1.4.3.jar; do CP="$CP:$j"; done
    mkdir -p /out/classes
    kotlinc -no-stdlib -module-name aws-ecs-server -Xskip-metadata-version-check -jvm-target 1.8 -cp "$CP" -d /out/classes /src/aws-ecs-server/src/main/kotlin > /out/kotlinc.log 2>&1 || { grep -v "^warning:" /out/kotlinc.log | head -80; echo "kotlinc FAILED"; exit 1; }
    grep -c "warning:" /out/kotlinc.log | sed "s/^/kotlinc warnings: /"
    # server jar = compiled classes + META-INF/Spring descriptor + buildServerResources
    mkdir -p /out/jar && cp -r /out/classes/. /out/jar/
    mkdir -p /out/jar/META-INF /out/jar/buildServerResources
    cp /src/aws-ecs-server/src/main/resources/META-INF/*.xml /out/jar/META-INF/
    cp -r /src/aws-ecs-server/src/main/resources/buildServerResources/. /out/jar/buildServerResources/
    (cd /out/jar && jar cf /out/aws-ecs-server.jar .)
    # plugin zip = existing installed plugin with the server jar swapped
    rm -rf /out/plugin && mkdir -p /out/plugin && cp -r /work/build/plugin/. /out/plugin/
    cp /out/aws-ecs-server.jar /out/plugin/server/aws-ecs-server.jar
    # make the build recognizable in Administration > Plugins
    sed -i "s|<version>\(.*\)</version>|<version>\1-awsconn.1</version>|" /out/plugin/teamcity-plugin.xml
    (cd /out/plugin && jar cMf /out/aws-ecs.zip .)
  '
echo "built: $OUT/aws-ecs.zip"
