# Docker for Convex CLI

FROM maven:3-openjdk-15

ENV HOME=/home/convex

WORKDIR $HOME

ADD . $HOME

RUN mvn clean package
