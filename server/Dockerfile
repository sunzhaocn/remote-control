FROM scratch
COPY --chmod=0555 dist/simplerc-server-linux-amd64 /usr/local/bin/simplerc-server
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/simplerc-server"]
