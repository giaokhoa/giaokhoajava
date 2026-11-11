# Đóng gói backend. Tầng build dùng Maven 3.10.0 + JDK 25 — đúng phiên bản
# wrapper của repo (backend/.mvn/wrapper/maven-wrapper.properties) và CI dùng;
# cổng chất lượng đầy đủ (unit test, Spotless, PMD, SpotBugs) do CI chạy bằng
# `clean verify` trước khi merge nên tầng này chỉ đóng gói, không chạy lại.
FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp package -DskipTests

# Tầng chạy: JRE 25 tối thiểu, chạy bằng user không phải root.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S giaokhoa && adduser -S -G giaokhoa giaokhoa
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER giaokhoa
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
