FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests

# Runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built JAR
COPY --from=build /app/target/kubernetes-agent-*.jar /app/kubernetes-agent.jar

# Create non-root user
RUN groupadd -r k8sagent && useradd -r -g k8sagent k8sagent
RUN chown -R k8sagent:k8sagent /app
USER k8sagent

EXPOSE 8080

ENV JAVA_OPTS="-Xmx1g -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/kubernetes-agent.jar"]


