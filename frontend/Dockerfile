# PNAS 前端镜像:多阶段构建(Vite 产物 → nginx 托管)
# 构建上下文:仓库根(见 deploy/docker-compose.yml)

# --- 构建阶段 ---
FROM node:22-alpine AS build
WORKDIR /app

# 先装依赖(利用缓存)
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install

# 构建
COPY frontend/ .
RUN npm run build

# --- 运行阶段 ---
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
