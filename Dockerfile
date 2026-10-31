FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system ominicat && useradd --system --gid ominicat ominicat
COPY --from=build /src/ominicat-server/target/ominicat-server.jar /app/ominicat-server.jar
COPY config /app/config
USER ominicat
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/ominicat-server.jar"]
CMD ["--config", "/app/config/container.yaml"]
