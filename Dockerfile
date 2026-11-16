FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
# 同时编译服务端和客户端：镜像内自带 assetsync 便于执行 delete-version / gc 等管理命令。
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ ./cmd/assetsync-server ./cmd/assetsync

FROM alpine:3.20
RUN adduser -D -u 10001 assetsync && mkdir -p /data /etc/assetsync && chown assetsync /data
COPY --from=build /out/assetsync-server /out/assetsync /usr/local/bin/
USER assetsync
VOLUME ["/data"]
EXPOSE 8080
# 将配置文件挂载到 /etc/assetsync/server.json（使用本地存储时 storage.path 应设为 /data）。
ENTRYPOINT ["assetsync-server", "serve", "-config", "/etc/assetsync/server.json"]
