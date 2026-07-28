# Prepare skgraph into ./.m2-ci first:
#   ./scripts/install-skgraph.sh
#   docker build -t team-jeopardy .

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY server/pom.xml server/pom.xml
COPY server/src server/src
COPY .m2-ci /root/.m2
RUN mvn -f server/pom.xml -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/target/team-jeopardy-server-0.1.0-SNAPSHOT.jar app.jar
COPY samples /app/samples
ENV TEAM_JEOPARDY_SAMPLE_CODE_PATH=/app/samples/sample-reactor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
