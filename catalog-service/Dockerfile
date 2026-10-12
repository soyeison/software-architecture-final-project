# syntax=docker/dockerfile:1
# Imagen del servicio de Catálogo. El contexto de construcción es la RAÍZ del repositorio,
# porque la compilación copia los contratos desde docs/contracts (fuente única de verdad).

# Etapa 1: compilación con JDK 25 (sin pruebas: se ejecutan con ./mvnw verify fuera de Docker).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY docs/contracts docs/contracts
COPY catalog-service/.mvn catalog-service/.mvn
COPY catalog-service/mvnw catalog-service/pom.xml catalog-service/
COPY catalog-service/src catalog-service/src
WORKDIR /workspace/catalog-service
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package
# Falla la construcción si el contrato no llegó al jar (p. ej., contexto sin docs/contracts).
RUN test -f target/classes/static/openapi/catalog-service.yaml

# Etapa 2: ejecución con un JRE 25 liviano.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/catalog-service/target/catalog-service-*.jar app.jar
# El servicio no necesita privilegios: corre con un usuario sin permisos especiales.
RUN addgroup -S app && adduser -S -G app app
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
