FROM maven:3.9-eclipse-temurin-25 AS base
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

FROM base AS dev
COPY . .
EXPOSE 8099
EXPOSE 9091
CMD ["mvn", "spring-boot:run"]

FROM base AS build
COPY . .
RUN mvn -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -ms /bin/bash appuser
COPY --from=build /app/target/*.jar /app/app.jar
USER appuser
EXPOSE 8099
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
