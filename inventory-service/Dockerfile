# syntax=docker/dockerfile:1
# Imagen del servicio de Inventario. El contexto de construcción es la RAÍZ del repositorio,
# porque la compilación copia los contratos desde docs/contracts (fuente única de verdad).

# Etapa 1: compilación con JDK 25 (sin pruebas: se ejecutan con ./mvnw verify fuera de Docker).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY docs/contracts docs/contracts
COPY inventory-service/.mvn inventory-service/.mvn
COPY inventory-service/mvnw inventory-service/pom.xml inventory-service/
COPY inventory-service/src inventory-service/src
WORKDIR /workspace/inventory-service
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package
# Falla la construcción si el contrato no llegó al jar (p. ej., contexto sin docs/contracts).
RUN test -f target/classes/static/openapi/inventory-service.yaml

# Etapa 2: ejecución con un JRE 25 liviano.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/inventory-service/target/inventory-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
