FROM gradle:7.4.2-jdk17 AS build

WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /home/gradle/src/build/install/BetPredictionBot/ /app/

RUN mkdir -p /app/logs

EXPOSE 7111 7222

CMD ["/app/bin/BetPredictionBot"]
