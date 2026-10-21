FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/* && groupadd --gid 10001 workbench && useradd --uid 10001 --gid 10001 --no-create-home workbench
WORKDIR /app
COPY --from=build /build/target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/.workbench && chown -R workbench:workbench /app
USER 10001:10001
ENV PORT=10000 SERVER_ADDRESS=0.0.0.0 DEMO_AUTH_REQUIRED=true DEMO_EPHEMERAL=true PLATFORM_RUNTIME=sandbox PLATFORM_DATA_DIR=/app/.workbench
EXPOSE 10000
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
