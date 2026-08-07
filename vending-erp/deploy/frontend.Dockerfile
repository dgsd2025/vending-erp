# 售卖机 ERP 前端生产镜像(ole 规范:node 构建 + nginx 静态托管)
FROM node:20-alpine AS build
WORKDIR /build
# 先设国内镜像源,再装 pnpm(国内 ECS 直连 npmjs 会超时)
RUN npm config set registry https://registry.npmmirror.com && npm i -g pnpm@9
COPY frontend/package.json frontend/pnpm-lock.yaml* frontend/pnpm-workspace.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install
COPY frontend/ .
RUN pnpm build

FROM nginx:alpine
COPY deploy/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
