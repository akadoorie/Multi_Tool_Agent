# --- Build stage: compile and package with Maven ---------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies first so this layer is cached unless pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Then build the application.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage: slim JRE with just the fat jar -------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# The app writes its task store under ./data, which docker-compose mounts as a volume.
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
