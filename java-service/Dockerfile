# Real, correct, but not built or run in this build environment: the Docker
# daemon was unavailable here (see README's "honest framing" section). This
# is a standard two-stage Spring Boot build, not a placeholder.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/batch-migration-differ.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
