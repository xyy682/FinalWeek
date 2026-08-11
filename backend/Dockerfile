FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn/settings.xml .mvn/settings.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -s .mvn/settings.xml dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -s .mvn/settings.xml package

FROM eclipse-temurin:21.0.8_9-jre-jammy
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ffmpeg libreoffice-impress fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home finalweek
WORKDIR /app
COPY --from=build /workspace/target/finalweek-backend-*.jar app.jar
USER finalweek
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
