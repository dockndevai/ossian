FROM node:22-alpine AS build
WORKDIR /app
# Lockfile first: npm ci is reproducible and skips resolution, and the layer survives source edits.
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

# The unprivileged variant: it listens on 8080 and owns the paths nginx writes to, so the
# container runs as a non-root user without patching permissions. The stock image cannot — its
# entrypoint needs to write /var/cache/nginx as root.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# templates/, not conf.d/: the entrypoint runs envsubst over anything here, which is what makes
# the API host configurable per environment. Dropped into conf.d it would be served verbatim and
# nginx would try to resolve the literal string "${OSSIAN_API_HOST}".
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# DOLLAR is how nginx's own $variables survive envsubst; see the template.
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS is opt-in: without it the base image's resolver script exits
# early, NGINX_LOCAL_RESOLVERS is never set, and the template's resolver directive is left with
# a literal ${NGINX_LOCAL_RESOLVERS} that nginx refuses to start on.
ENV OSSIAN_API_HOST=ossian-backend:8081 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    DOLLAR="$"
EXPOSE 8080
