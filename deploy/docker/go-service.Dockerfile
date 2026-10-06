# syntax=docker/dockerfile:1.10
#
# Fortress Go service image: one Dockerfile for every service.
#
#   docker build -f deploy/docker/go-service.Dockerfile \
#     --build-arg SERVICE=offline-mesh --build-arg VERSION=$(git describe) \
#     --build-arg COMMIT=$(git rev-parse HEAD) -t fortress/offline-mesh .
#
# Supply-chain notes
#  * Renovate pins both base images by digest (FROM image:tag@sha256:...); tags
#    are shown here for readability. CI rejects unpinned images on main.
#  * Services are stdlib-only, so `go mod verify` covers the whole graph.
#  * Final image: no shell, no package manager, no libc, UID 65532, ~10 MB.

ARG GO_VERSION=1.27.1

# ───────────────────────────── 1. build ─────────────────────────────
FROM golang:${GO_VERSION}-trixie AS build

ARG SERVICE
ARG VERSION=dev
ARG COMMIT=unknown

# Fail fast on a missing or path-traversing SERVICE argument.
RUN case "${SERVICE}" in \
      core-ledger|identity|offline-mesh|payments|security-gateway) ;; \
      *) echo "SERVICE must be one of core-ledger|identity|offline-mesh|payments|security-gateway" >&2; exit 1 ;; \
    esac

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOWORK=off \
    GOTOOLCHAIN=local \
    GOFLAGS="-trimpath -mod=readonly -buildvcs=false"

WORKDIR /src
# Copy only what this service compiles: shared modules + the one service.
COPY shared/ shared/
COPY services/${SERVICE}/ services/${SERVICE}/

WORKDIR /src/services/${SERVICE}
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    go mod verify && \
    go vet ./... && \
    go build \
      -tags "netgo,osusergo" \
      -ldflags "-s -w -buildid= -X main.version=${VERSION} -X main.commit=${COMMIT}" \
      -o /out/service ./cmd/${SERVICE}

# ───────────────────────────── 2. test (optional target) ─────────────
# `docker build --target test …` runs the race-free unit suite inside the
# exact build environment. CI runs it separately with -race on the host.
FROM build AS test
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    go test -count=1 ./...

# ───────────────────────────── 3. runtime ───────────────────────────
FROM gcr.io/distroless/static-debian13:nonroot AS runtime

ARG SERVICE
ARG VERSION=dev
ARG COMMIT=unknown

LABEL org.opencontainers.image.title="fortress-${SERVICE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}" \
      org.opencontainers.image.vendor="Fortress" \
      org.opencontainers.image.licenses="Proprietary"

# distroless/static ships CA certificates and tzdata; nothing else is needed.
COPY --from=build --chown=65532:65532 --chmod=0555 /out/service /app/service

# Explicit numeric UID:GID so Kubernetes `runAsNonRoot` can verify it.
USER 65532:65532
WORKDIR /app
EXPOSE 8080

# Distroless has no curl/sh: the binary probes itself. Kubernetes uses its own
# liveness/readiness probes; this covers docker/compose and image scanners.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/app/service", "-healthcheck"]

ENTRYPOINT ["/app/service"]
