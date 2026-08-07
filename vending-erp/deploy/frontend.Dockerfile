# 售卖机 ERP 前端生产镜像(ole 规范:node 构建 + nginx 静态托管)
FROM node:20-alpine AS build
WORKDIR /build
RUN npm i -g pnpm@9 && npm config set registry https://registry.npmmirror.com
COPY frontend/package.json frontend/pnpm-lock.yaml* frontend/pnpm-workspace.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install
COPY frontend/ .
RUN pnpm build

FROM nginx:alpine
COPY deploy/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
