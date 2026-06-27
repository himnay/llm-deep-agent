FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Install private parent POMs that are not in any public Maven registry
COPY super-pom/pom.xml super-pom.xml
COPY maven-bom/pom.xml learning-bom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository/com/org/llm/super-pom/1.0.0 \
    && cp super-pom.xml /root/.m2/repository/com/org/llm/super-pom/1.0.0/super-pom-1.0.0.pom \
    && mkdir -p /root/.m2/repository/com/org/learning/learning-bom/1.0.0 \
    && cp learning-bom.xml /root/.m2/repository/com/org/learning/learning-bom/1.0.0/learning-bom-1.0.0.pom

# Resolve dependencies separately so this layer is cached until pom.xml changes
COPY LLM/llm-deep-agent/mvnw LLM/llm-deep-agent/pom.xml ./
COPY LLM/llm-deep-agent/.mvn .mvn/
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -q

COPY LLM/llm-deep-agent/src src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jre AS extract
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=extract /app/dependencies/ ./
COPY --from=extract /app/spring-boot-loader/ ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/ ./
USER spring:spring
EXPOSE 8090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8090/orchestrator/v1/actuator/health || exit 1
