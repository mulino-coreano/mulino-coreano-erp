FROM node:24.16.0-bookworm-slim@sha256:2c87ef9bd3c6a3bd4b472b4bec2ce9d16354b0c574f736c476489d09f560a203
WORKDIR /opt/mulino
COPY package.json package-lock.json ./
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
    && ln -s /opt/mulino/node_modules/.bin/codex /usr/local/bin/codex \
    && groupadd --gid 10001 mulino \
    && useradd --uid 10001 --gid 10001 --create-home mulino \
    && mkdir -p /home/mulino/.codex /work \
    && chown 10001:10001 /home/mulino/.codex /work \
    && npm cache clean --force
COPY --chmod=0555 mulino /usr/local/bin/mulino
COPY result.schema.json ./
COPY skills/ ./skills/
ENV HOME=/home/mulino CODEX_HOME=/home/mulino/.codex
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["codex"]
CMD ["--version"]
