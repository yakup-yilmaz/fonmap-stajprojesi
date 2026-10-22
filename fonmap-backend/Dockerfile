# ==============================================================================
# Multi-Stage Build Dockerfile for FonMap Backend
# ------------------------------------------------------------------------------
# 1. Aşama: Temiz checkout sonrası harici Java/Maven ihtiyacı olmaksızın derleme
# 2. Aşama: Yalnızca JRE içeren minimal ve güvenli çalışma ortamı
# ==============================================================================

# 1. AŞAMA: DERLEME (BUILD STAGE)
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Maven modül tanımlarını kopyala
COPY pom.xml .
COPY fonmap-domain/pom.xml fonmap-domain/
COPY fonmap-infrastructure/pom.xml fonmap-infrastructure/
COPY fonmap-application/pom.xml fonmap-application/
COPY fonmap-api/pom.xml fonmap-api/

# Kaynak kodları kopyala
COPY fonmap-domain/src fonmap-domain/src
COPY fonmap-infrastructure/src fonmap-infrastructure/src
COPY fonmap-application/src fonmap-application/src
COPY fonmap-api/src fonmap-api/src

# Projeyi container içinde bağımsız derle ve paketle
RUN mvn clean package -DskipTests

# 2. AŞAMA: ÇALIŞTIRMA (RUNTIME STAGE)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Derleme aşamasından üretilen JAR'ı kopyala
COPY --from=builder /build/fonmap-api/target/fonmap-api-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
