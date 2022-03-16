FROM gradle:jdk17 AS build-env
WORKDIR /opt
COPY . .
RUN ./gradlew shadowJar

FROM openjdk:17-slim
COPY --from=build-env /opt/build/libs/opengferelay-all.jar /opt

EXPOSE 47984/tcp \
       47989/tcp \
       48010/tcp \
       47998/udp \
       47999/udp \
       48000/udp \
       48002/udp \
       48010/udp

VOLUME /opt/keys
WORKDIR /opt/keys
ENTRYPOINT ["java", "-jar", "/opt/opengferelay-all.jar"]
