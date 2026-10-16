# Build:  docker build -t testlum-mcp:0.1.0 mcp/
# Engine: built on the pinned engine image so the engine jar and Java 17 come with it.
#         To use another engine (for example the fork with JUnit output), mount its jar over
#         /testlum/testlum-final.jar or pass --build-arg ENGINE_IMAGE=<image>.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

ARG ENGINE_IMAGE=ghcr.io/testlumframework/testlum:2.0.0
FROM ${ENGINE_IMAGE}
LABEL app="testlum-mcp"
COPY --from=build /src/target/testlum-mcp.jar /opt/testlum-mcp/testlum-mcp.jar
ENV TESTLUM_ENGINE_JAR=/testlum/testlum-final.jar \
    TESTLUM_MCP_IDLE_SECONDS=900
WORKDIR /work
CMD []
ENTRYPOINT ["java", "-jar", "/opt/testlum-mcp/testlum-mcp.jar"]
