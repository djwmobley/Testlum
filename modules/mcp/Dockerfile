# Build from the repository root:  docker build -f modules/mcp/Dockerfile -t testlum-mcp:0.5.0 .
# The engine is built from the same checkout, so the image always has the jsonPath assertion,
# the field-level diffs and the JUnit report that this checkout carries. To use another engine,
# mount its jar over /testlum/testlum-final.jar (TESTLUM_ENGINE_JAR_HOST in the launchers).
ARG BASE_IMAGE=eclipse-temurin:17-jre

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests -Dcheckstyle.skip -Dspotbugs.skip -Pmcp -pl engine,modules/mcp -am package \
 && cp engine/target/testlum-*.jar /testlum-final.jar \
 && cp modules/mcp/target/testlum-mcp.jar /testlum-mcp.jar

FROM ${BASE_IMAGE}
LABEL app="testlum-mcp"
COPY --from=build /testlum-final.jar /testlum/testlum-final.jar
COPY --from=build /testlum-mcp.jar /opt/testlum-mcp/testlum-mcp.jar
ENV TESTLUM_ENGINE_JAR=/testlum/testlum-final.jar \
    TESTLUM_MCP_IDLE_SECONDS=900
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/testlum-mcp/testlum-mcp.jar"]
