# Reproducible build (Maven & JDK 21); binary remains compatible with Java 17+.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY server/pom.xml server/pom.xml
RUN mvn -ntp -f server/pom.xml dependency:go-offline
COPY server/src server/src
RUN mvn -ntp -f server/pom.xml -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data/media
COPY --from=build /src/server/target/akiat-server-0.1.0-SNAPSHOT.jar /app/akiat-server.jar
ENV AKIAT_BIND=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/akiat-server.jar"]
