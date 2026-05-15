# ─── Stage 1: Build ───────────────────────────────────────────────────────────
# Node 20 LTS on Alpine. The builder stage runs the TypeScript compile and Vite
# production build, producing a static asset bundle in /app/dist.
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package manifests first so Docker caches node_modules separately from
# source code. The glob picks up package-lock.json when present (faster,
# reproducible via npm ci) and falls back to npm install when it is absent.
COPY package*.json ./
RUN if [ -f package-lock.json ]; then npm ci --silent; else npm install --silent; fi

# Copy the rest of the source and build for production.
# VITE_ variables must be provided at build time (they are inlined by Vite).
# Pass them as --build-arg and expose them as ARG/ENV below.
# The default values are empty strings so the image builds without errors
# even if the variables are not supplied; the app will call the backend at
# the default relative URL.
ARG VITE_API_BASE_URL=""
ARG VITE_LICENSE_KEY=""
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_LICENSE_KEY=${VITE_LICENSE_KEY}

COPY . .
RUN npm run build

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# Nginx Alpine serves the static Vite build output.
# The container is stateless — all dynamic data comes from API calls to the backend.
FROM nginx:alpine AS runtime

# Remove the default Nginx config and replace with our own.
# Our config:
#   • Serves static assets from /usr/share/nginx/html
#   • Sets long cache TTLs on hashed assets (immutable) and short TTL on index.html
#   • Proxies /api/* to the backend service so the browser sees a single origin
#   • Returns index.html for unknown paths (client-side routing)
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the compiled assets from the builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Security: drop privileges — Nginx master process runs as root to bind port 80,
# but worker processes run as the built-in nginx user.
# For production behind a TLS terminating proxy, port 80 is fine inside the
# container; TLS is handled by the load balancer / reverse proxy.
EXPOSE 80

# Docker HEALTHCHECK: request the root page and expect 200.
# Marked healthy once Nginx is serving the index file.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://localhost:80/ > /dev/null || exit 1

CMD ["nginx", "-g", "daemon off;"]
