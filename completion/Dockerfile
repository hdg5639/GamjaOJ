FROM eclipse-temurin@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438 AS jdk
FROM eclipse-temurin@sha256:d57e5d0e3e5dd4cabb74feccfdb36249a58c76a3354a33ee8737813df93b7e0d AS java8
FROM python@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e
COPY --from=jdk /opt/java/openjdk /opt/java21
COPY --from=java8 /opt/java/openjdk /opt/java8
RUN apt-get update && apt-get install -y --no-install-recommends clangd g++ curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir jedi==0.19.2 parso==0.8.4
RUN curl -fsSL https://download.eclipse.org/jdtls/milestones/1.46.1/jdt-language-server-1.46.1-202504011455.tar.gz -o /tmp/jdtls.tar.gz \
 && echo 'f435fdf6db3aa0d1598ef0f11f80bb20e09e67009001382719c313eff07de02c  /tmp/jdtls.tar.gz' | sha256sum -c - \
 && mkdir /opt/jdtls && tar -xzf /tmp/jdtls.tar.gz -C /opt/jdtls && rm /tmp/jdtls.tar.gz
WORKDIR /service
COPY completion/server.py completion/python_worker.py ./
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp
USER 65534:65534
EXPOSE 8090
CMD ["python", "server.py"]
