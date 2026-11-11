# Đóng gói frontend Next.js. Repo không có next.config nên output standalone
# không bật: tầng chạy dùng đúng script `start` của package.json
# (next start, cổng 3001) trên cây đã build. Node 24 khớp phiên bản CI dùng.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build
# Build xong mới bỏ devDependencies khỏi node_modules cho tầng chạy.
RUN npm prune --omit=dev

FROM node:24-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/.next ./.next
COPY --from=build --chown=node:node /app/node_modules ./node_modules
USER node
EXPOSE 3001
CMD ["npm", "run", "start"]
