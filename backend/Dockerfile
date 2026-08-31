# Dependencies first, in their own layer.
#
# Copying the POMs alone and resolving against them means a source-only change reuses the
# downloaded dependencies. Copying everything at once would re-resolve the whole tree on every
# commit, which turns a thirty-second rebuild into a five-minute one.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
COPY backend/pom.xml backend/
COPY clients/java/pom.xml clients/java/
RUN mvn -B -q -pl backend -am dependency:go-offline -DskipTests

FROM deps AS build
COPY backend/src backend/src
COPY clients/java/src clients/java/src
# Tests run in CI against a real Postgres; running them here would need Docker inside Docker.
RUN mvn -B -q -pl backend -am package -DskipTests \
 && mv backend/target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre AS runtime

# Tika reads fonts and encodings from the base image for some formats; keep the JRE image rather
# than a distroless one, where a PDF with an embedded font fails at parse time and not at build.
RUN groupadd --system --gid 1001 ossian \
 && useradd --system --uid 1001 --gid ossian --home /app ossian
WORKDIR /app
COPY --from=build --chown=ossian:ossian /build/app.jar app.jar
USER 1001

EXPOSE 8081

# Container-aware defaults. Without MaxRAMPercentage the JVM sizes its heap from the host's
# memory, not the cgroup limit, and gets OOM-killed by the kernel rather than throwing a
# manageable OutOfMemoryError.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. Wrapped in a shell it would not,
# and every rollout would hard-kill in-flight ingestion after the grace period instead of
# draining.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
