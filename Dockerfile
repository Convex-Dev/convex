# Docker for Convex 

#######################################
# Build stage
FROM maven:3.9.9-eclipse-temurin-22-jammy AS build
WORKDIR /build
COPY . .
RUN mvn clean install -DskipTests

#######################################
# Run stage
FROM eclipse-temurin:22-jre-alpine AS run

# Add labels
LABEL org.opencontainers.image.title="Convex"
LABEL org.opencontainers.image.description="Convex Peer Node"
LABEL org.opencontainers.image.source="https://github.com/Convex-Dev/convex"
LABEL org.opencontainers.image.source="https://convex.world"

# Create non-root user
RUN addgroup -S convex && adduser -S convex -G convex

# Set environment variables
ENV HOME=/home/convex \
    CONVEX_HTTP_PORT=8080 \
    CONVEX_BINARY_PORT=18888 \
    CONVEX_HTTPS_PORT=443

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
EXPOSE $CONVEX_HTTPS_PORT

# Define volumes
VOLUME ["/etc/convex/keystore"]

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${CONVEX_HTTP_PORT}/api/v1/status || exit 1

ENTRYPOINT ["java", "-jar", "convex.jar", "peer", "start"]

