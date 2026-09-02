FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S ardor && adduser -S ardor -G ardor
RUN mkdir -p /app/data/resumes && chown -R ardor:ardor /app/data
COPY target/ardor-backend-0.1.0-SNAPSHOT.jar /app/ardor.jar
USER ardor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ardor.jar"]
