# Docker for Convex 

# FROM maven:3.9.9-eclipse-temurin-22 AS build
# #ENV HOME=/home/convex
# WORKDIR $HOME
# ADD . $HOME
# RUN mvn clean package

# Run stage

FROM eclipse-temurin:22-jdk-alpine AS run
ENV HOME=/home/convex
WORKDIR $HOME
COPY ./convex-integration/target/convex-jar-with-dependencies.jar convex.jar

# Expose ports. These can be mapped to host ports
EXPOSE 18888
EXPOSE 8080
EXPOSE 443

VOLUME ["/etc/ssl/certs"]
VOLUME ["/etc/convex/keystore"]

ENTRYPOINT ["java", "-jar", "convex.jar", "local", "start"]

