FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY urlshortener ./urlshortener
COPY resources ./resources
COPY URL_Shortener.iml ./URL_Shortener.iml
RUN mkdir -p build/classes \
 && find urlshortener -name "*.java" > sources.txt \
 && javac -encoding UTF-8 -d build/classes @sources.txt
CMD ["java", "-cp", "build/classes", "urlshortener.bootstrap.ManagementMain"]
