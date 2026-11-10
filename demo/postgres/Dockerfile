FROM postgres:16.15-bookworm AS hypopg-build

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates curl postgresql-server-dev-16 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl --fail --location --retry 3 https://codeload.github.com/HypoPG/hypopg/tar.gz/refs/tags/1.4.2 \
      | tar xz --strip-components=1 \
    && make PG_CONFIG=/usr/lib/postgresql/16/bin/pg_config with_llvm=no \
    && make PG_CONFIG=/usr/lib/postgresql/16/bin/pg_config with_llvm=no install

FROM postgres:16.15-bookworm
COPY --from=hypopg-build /usr/lib/postgresql/16/lib/hypopg.so /usr/lib/postgresql/16/lib/
COPY --from=hypopg-build /usr/share/postgresql/16/extension/hypopg* /usr/share/postgresql/16/extension/
