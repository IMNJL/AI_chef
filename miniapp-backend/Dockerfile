FROM eclipse-temurin:21-jre

WORKDIR /app

ARG JAR_FILE
COPY ${JAR_FILE} app.jar

EXPOSE 8010

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
