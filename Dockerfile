# syntax=docker/dockerfile:1
# 사용자 앱·관리자 앱 이미지. 빌드 단계는 둘이 같아서 한 번만 돈다(compose가 target으로 고른다)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :user:bootJar :admin:bootJar --no-daemon \
 && mkdir /out \
 && cp $(ls user/build/libs/*.jar | grep -v -- -plain) /out/user.jar \
 && cp $(ls admin/build/libs/*.jar | grep -v -- -plain) /out/admin.jar

FROM eclipse-temurin:17-jre AS runtime
# compose healthcheck가 쓴다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Seoul

FROM runtime AS user
COPY --from=build /out/user.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM runtime AS admin
COPY --from=build /out/admin.jar /app.jar
# 8081 웹, 8082 내부 API(/internal/**)
EXPOSE 8081 8082
ENTRYPOINT ["java", "-jar", "/app.jar"]
