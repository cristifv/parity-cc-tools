# parity-cc-tools' image (PLAN.md §8). The JRE: Temurin 21 on Ubuntu noble, pinned by digest (as
# parity-frontend and parity-telephony-controller; built 2026-09-16, the 14-day rule). Runs as UID 10001
# with a read-only root filesystem: the deployment mounts /tmp as an emptyDir (the health and drain
# files, Tomcat's work directory; exec, since the Kafka codecs load native libraries from it), the secrets
# read-only at /run/secrets, the service catalogue at /etc/parity-cc-tools/catalogue.yaml.
FROM eclipse-temurin:21.0.12_8-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f
COPY target/parity-cc-tools.jar /app/app.jar
USER 10001:10001
EXPOSE 8095 8096
ENV PARITY_CC_TOOLS_SECRETS_DIR=/run/secrets/ \
    PARITY_CC_TOOLS_CATALOGUE=/etc/parity-cc-tools/catalogue.yaml
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
