# Build both modules, then run the chat server with the crawler CLI next to it.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY crawler-cli/pom.xml crawler-cli/
COPY chat-server/pom.xml chat-server/
RUN mvn -q -B dependency:go-offline || true
COPY . .
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/crawler-cli/target/sitecrawl.jar /app/sitecrawl.jar
COPY --from=build /src/chat-server/target/chat-server.jar /app/chat-server.jar
USER app
ENV CRAWLER_JAR=/app/sitecrawl.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/chat-server.jar"]
