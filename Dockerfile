# ---- Build stage ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080

# 512MB 컨테이너(Railway free)를 전제로 한 설정.
#
# 왜 필요한가: JDK 17 은 컨테이너를 인식해 힙을 512MB 의 25%(=128MB)로 잡지만, 제한하는 것은
# 힙뿐이다. Tomcat 스레드·Metaspace·코드 캐시·다이렉트 버퍼는 서버급 머신을 가정한 기본값이고
# 합계 상한이 없다. 그래서 힙이 절반도 안 찬 상태로 총 RSS 가 한도를 넘어 커널에 죽는다 —
# OutOfMemoryError 스택도 남지 않는다. 2026-07-24 배포가 정확히 이렇게 죽었다
# (정상 412MB = 한도의 80%, 재배포 버스트에서 654MB → OOM-kill → 크래시 루프).
#
# MaxRAMPercentage 는 기본값과 같은 25 를 의도적으로 명시했다 — 힙은 원인이 아니었으므로 늘리지 않는다.
# TieredStopAtLevel=1 은 C2 JIT 를 끄고 코드 캐시와 컴파일러 메모리를 아낀다. 이 앱의 병목은
# OpenAI API 지연이라 최고 처리량을 포기해도 체감 차이가 없다.
# ExitOnOutOfMemoryError 는 힙이 진짜 차면 즉시 죽여 재시작하게 한다 — GC 스래싱보다 낫다.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=25", \
  "-XX:+UseSerialGC", \
  "-XX:TieredStopAtLevel=1", \
  "-Xss512k", \
  "-XX:MaxMetaspaceSize=160m", \
  "-XX:ReservedCodeCacheSize=64m", \
  "-XX:MaxDirectMemorySize=64m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
