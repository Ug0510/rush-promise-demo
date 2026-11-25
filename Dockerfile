FROM eclipse-temurin:21-jre
WORKDIR /opt/rush
COPY target/rush-promise-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/rush/app.jar"]
