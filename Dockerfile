# Imagen única para todos los servicios: docker build --build-arg MODULE=ms-accounts .
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:17-jre
ARG MODULE
RUN useradd --system --uid 1001 bytebank
WORKDIR /app
COPY --from=build /src/${MODULE}/target/app.jar app.jar
USER bytebank
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
