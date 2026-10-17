# Build:  docker build -t testlum-mcp:0.3.0 mcp/
# The engine is the djwmobley fork at a pinned commit (stock 2.0.0 plus the jsonPath assertion, field-level diffs and JUnit output),
# so per-scenario results and the scenario-count gate work with no jar mounted.
# Another engine: mount its jar over /testlum/testlum-final.jar (TESTLUM_ENGINE_JAR_HOST in the
# launchers) or build with --build-arg ENGINE_REF=<commit> / ENGINE_REPO=<url>.
ARG BASE_IMAGE=eclipse-temurin:17-jre
ARG ENGINE_REPO=https://github.com/djwmobley/Testlum.git
ARG ENGINE_REF=f52dc9a4820173ffd01c280c62befefea03e44cc

FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM maven:3.9-eclipse-temurin-17 AS engine
ARG ENGINE_REPO
ARG ENGINE_REF
WORKDIR /engine
RUN git init -q . \
 && git fetch -q --depth 1 "$ENGINE_REPO" "$ENGINE_REF" \
 && git checkout -q FETCH_HEAD \
 && mvn -B -q -DskipTests -Dcheckstyle.skip -Dspotbugs.skip -Dmaven.javadoc.skip package \
 && cp engine/target/testlum-*.jar /testlum-final.jar

FROM ${BASE_IMAGE}
LABEL app="testlum-mcp"
COPY --from=engine /testlum-final.jar /testlum/testlum-final.jar
COPY --from=server /src/target/testlum-mcp.jar /opt/testlum-mcp/testlum-mcp.jar
ENV TESTLUM_ENGINE_JAR=/testlum/testlum-final.jar \
    TESTLUM_MCP_IDLE_SECONDS=900
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/testlum-mcp/testlum-mcp.jar"]
