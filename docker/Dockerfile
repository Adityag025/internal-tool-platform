# Multi-stage, layer-cached production image.
# Compare with Dockerfile.baseline, which does all of this wrong on purpose.
#
# The whole design turns on one idea: ORDER LAYERS BY HOW OFTEN THEY CHANGE.
# Docker caches a layer only if that layer and every layer before it are
# unchanged. Dependencies change monthly; source changes hourly. So
# dependencies must be resolved BEFORE the source is copied in, or every
# one-character edit throws away the dependency download.

# ---------------------------------------------------------------- stage 1
FROM eclipse-temurin:21-jdk AS dependencies
WORKDIR /build

# Only the build descriptors. This layer is invalidated by a pom.xml change
# and by nothing else - so it survives across almost every commit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Pre-populate the local repository. With BuildKit's cache mount, ~/.m2 also
# persists between builds on the same machine, so even a pom change is cheap.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q dependency:go-offline -DskipTests

# ---------------------------------------------------------------- stage 2
FROM dependencies AS build
# Source comes in LAST, after the expensive dependency layer is already cached.
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q clean package -DskipTests

# ---------------------------------------------------------------- stage 3
# JRE, not JDK. The compiler, the source tree and ~/.m2 do not ship: nothing
# that is not needed at runtime should be in a production image, both for size
# and because every megabyte is attack surface.
FROM eclipse-temurin:21-jre AS runtime

# Never run as root. A container escape starts from whatever the process owns.
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --home /app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/tool-registry.jar app.jar

# Create the artifact directory in the IMAGE, owned by the app user.
# When Docker mounts a named volume over a path that already exists in the
# image, it seeds the empty volume with that path's contents AND ownership.
# Skip this and the volume arrives root-owned, and a container that correctly
# runs as non-root cannot write to it.
RUN mkdir -p /var/lib/tool-platform/artifacts && chown -R app:app /var/lib/tool-platform

USER app

EXPOSE 8081

# Lets Docker/ECS know the difference between "process started" and "ready to
# serve" - the same distinction the fixed sleeps in the baseline got wrong.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
  CMD ["sh", "-c", "wget -q -O- http://localhost:8081/actuator/health/readiness || exit 1"]

# exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell
# form, the shell is PID 1, swallows the signal, and the container is killed
# instead of shutting down gracefully.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
