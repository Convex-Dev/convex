# Docker for Convex

#######################################
# Build stage
# JDK matches CI (build.yml / release.yml run on JDK 25), so the peer image runs
# the exact JDK the pipeline tested. The project targets Java 21 bytecode
# (maven.compiler.release=21) for library reach, which runs forward-compatibly on
# this JDK 25. Bump together with CI.
FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /build

# Copy POMs first for dependency caching
COPY pom.xml .
COPY convex-core/pom.xml convex-core/
COPY convex-peer/pom.xml convex-peer/
COPY convex-cli/pom.xml convex-cli/
COPY convex-gui/pom.xml convex-gui/
COPY convex-restapi/pom.xml convex-restapi/
COPY convex-java/pom.xml convex-java/
COPY convex-benchmarks/pom.xml convex-benchmarks/
COPY convex-observer/pom.xml convex-observer/
COPY convex-integration/pom.xml convex-integration/
RUN mvn dependency:go-offline -B || true

# Copy source and build. Tests are skipped: every imaged commit is already
# tested by CI on the same JDK (build.yml on push, release.yml on tag), and the
# reproducible-build configuration makes this rebuild equivalent.
COPY . .
RUN mvn -B clean install -DskipTests

#######################################
# Run stage
# JRE matches the JDK everything is built and tested on (see build stage note)
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.title="Convex" \
      org.opencontainers.image.description="Convex Peer Node" \
      org.opencontainers.image.source="https://github.com/Convex-Dev/convex" \
      org.opencontainers.image.url="https://convex.world"

# Create non-root user
RUN addgroup -S convex && adduser -S convex -G convex

# Set environment variables
ENV HOME=/home/convex \
    CONVEX_HTTP_PORT=8080 \
    CONVEX_BINARY_PORT=18888

WORKDIR $HOME

# Copy application jar from build stage
COPY --from=build /build/convex-integration/target/convex.jar convex.jar

# Set proper permissions
RUN chown -R convex:convex $HOME && \
    chmod 500 convex.jar

# Create and set permissions for volumes
RUN mkdir -p /etc/convex/keystore && \
    chown -R convex:convex /etc/convex

# Switch to non-root user
USER convex

# Expose ports
EXPOSE $CONVEX_BINARY_PORT
EXPOSE $CONVEX_HTTP_PORT

# Define volumes
VOLUME ["/etc/convex/keystore"]

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${CONVEX_HTTP_PORT}/api/v1/status || exit 1

ENTRYPOINT ["java", "-jar", "convex.jar", "peer", "start"]
