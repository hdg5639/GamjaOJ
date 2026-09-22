FROM eclipse-temurin@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438
WORKDIR /app
COPY --chmod=0444 backend/target/gamjaoj.jar /app/app.jar
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
