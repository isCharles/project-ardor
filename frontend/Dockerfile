FROM node:24-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM dependencies AS build
ARG ARDOR_BACKEND_URL=http://127.0.0.1:8080
ENV ARDOR_BACKEND_URL=$ARDOR_BACKEND_URL
COPY . .
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
RUN apk add --no-cache texlive-xetex texmf-dist-latexextra texmf-dist-langchinese font-noto-cjk ghostscript
ENV ARDOR_LATEX_ENGINE=xelatex
COPY --from=dependencies --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/.next ./.next
COPY --from=build --chown=node:node /app/public ./public
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/next.config.ts ./next.config.ts
USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
