FROM gradle:jdk14 AS build-env
WORKDIR /opt
COPY . .
RUN ./gradlew shadowJar

FROM openjdk:14-alpine
COPY --from=build-env /opt/build/libs/opengferelay-all.jar /opt
USER nobody

EXPOSE 47984/tcp \
       47989/tcp \
       48010/tcp \
       47998/udp \
       47999/udp \
       48000/udp \
       48002/udp \
       48010/udp

ENTRYPOINT ["java", "-jar", "/opt/opengferelay-all.jar"]