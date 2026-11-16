FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
# 同时编译服务端和客户端：镜像内自带 qentimart 便于执行 delete-version / gc 等管理命令。
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ ./cmd/qentimart-server ./cmd/qentimart

FROM alpine:3.20
RUN adduser -D -u 10001 qentimart && mkdir -p /data /etc/qentimart && chown qentimart /data
COPY --from=build /out/qentimart-server /out/qentimart /usr/local/bin/
USER qentimart
VOLUME ["/data"]
EXPOSE 8080
# 将配置文件挂载到 /etc/qentimart/server.json（使用本地存储时 storage.path 应设为 /data）。
ENTRYPOINT ["qentimart-server", "serve", "-config", "/etc/qentimart/server.json"]
