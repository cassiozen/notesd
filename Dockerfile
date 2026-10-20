# The release binaries (mise run release) in a minimal image; see docs/hosting.md.
FROM --platform=$BUILDPLATFORM busybox:1.37 AS data
RUN mkdir /data

FROM gcr.io/distroless/static-debian12:nonroot
ARG TARGETARCH
COPY --chmod=755 server/bin/notesd-linux-${TARGETARCH} /usr/local/bin/notesd
# Owned by nonroot, so a new named volume is writable without a chown.
COPY --from=data --chown=nonroot:nonroot /data /data
ENV NOTESD_LISTEN=0.0.0.0:8080 \
    NOTESD_DATA_DIR=/data
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["notesd"]
