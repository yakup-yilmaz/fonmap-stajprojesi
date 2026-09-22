FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Proje derlendiğinde oluşan JAR dosyasını kopyalayacağız
# İleride fonmap-api modülünü tamamlayıp "mvn clean package" dediğimizde bu JAR oluşacak
COPY fonmap-api/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
