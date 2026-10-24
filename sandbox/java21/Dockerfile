FROM maven:3.9.16-eclipse-temurin-21
LABEL xcloud.profile="java21-junit6-v1"
COPY sandbox/java21/pom.xml /opt/runner/pom.xml
RUN mkdir -p /workspace/src/main/java /workspace/src/test/java && \
    printf '%s\n' 'class Warmup {}' > /workspace/src/main/java/Warmup.java && \
    printf '%s\n' 'import org.junit.jupiter.api.Test; class RunnerWarmupTest { @Test void ready() {} }' > /workspace/src/test/java/RunnerWarmupTest.java && \
    mvn -B -ntp -f /opt/runner/pom.xml -Dmaven.repo.local=/opt/runner/repository -Dtest=RunnerWarmupTest test && \
    rm -rf /workspace/src /tmp/build && chmod -R a+rX /opt/runner
WORKDIR /workspace
