# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test -q

# --- run ---
FROM eclipse-temurin:21-jre
# pdftotext reads PDF text layers; tesseract is the offline OCR fallback for scanned images.
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils tesseract-ocr tesseract-ocr-fra \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
# Run as an unprivileged user. /storage is created here and owned by that user, so a fresh named volume
# mounted there inherits the ownership (an old root-owned volume needs `docker compose down -v` once).
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /storage && chown app:app /app /storage
WORKDIR /app
COPY --from=build --chown=app:app /src/build/libs/*.jar app.jar
ENV APP_DATA_DIR=/data APP_STORAGE_DIR=/storage SERVER_PORT=8088
USER app
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
