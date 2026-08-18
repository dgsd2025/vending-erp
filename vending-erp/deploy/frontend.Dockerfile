# 售卖机 ERP 前端生产镜像(ole 规范:node 构建 + nginx 静态托管)
# Node 22:对齐本地环境(pnpm@11 需 Node≥22.13,用到 node:sqlite;Node20 会 ERR_UNKNOWN_BUILTIN_MODULE)
FROM node:22-alpine AS build
WORKDIR /build
# CI 模式:pnpm 无终端时不弹交互确认(NO_TTY abort 防复发)
ENV CI=true
# 先设国内镜像源,再装 pnpm(国内 ECS 直连 npmjs 会超时)
# pnpm 版本对齐本地 11.x:pnpm-workspace.yaml 用的 allowBuilds 是 pnpm10+ 字段
RUN npm config set registry https://registry.npmmirror.com && npm i -g pnpm@11
COPY frontend/package.json frontend/pnpm-lock.yaml* frontend/pnpm-workspace.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install
COPY frontend/ .
RUN pnpm build

FROM nginx:alpine
COPY deploy/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
