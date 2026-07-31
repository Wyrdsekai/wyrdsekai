# Third-Party Notices

Wyrdsekai is licensed under the Apache License, Version 2.0 (see `LICENSE`).
This file lists the third-party components distributed with the Wyrdsekai
server (the installed jar tree produced from the `:server` runtime
classpath) and the binaries and model assets bundled by the installers, with
their licenses. The `:cli`, `:clients:daemon-desktop`, and `:rendezvous`
jar trees shipped alongside add only two artifacts beyond the server set:
`org.jline:jline` (BSD-3-Clause) and `net.i2p.crypto:eddsa` (CC0-1.0).

**Bundled script:** `scripts/lib/wyrd_qr.py` is an independent implementation
of the ISO/IEC 18004 QR standard (rendering the `wyrd phone invite` QR with
only python3). Its constant data tables (Reed-Solomon block structure,
alignment-pattern positions, BCH generators) are derived from the
**`qrcode`** Python package (v8.2), **BSD-3-Clause**, © Lincoln Loop; its
output is verified byte-identical to that reference.

Generated 2026-07-21 from `./gradlew :server:dependencies --configuration
runtimeClasspath` (239 artifacts) with license names resolved from each
artifact's published Maven POM (walking parent POMs where the license is
declared there). To regenerate after a dependency change, re-run that report
and refresh the table below.

## Summary

There is **no strong-copyleft (GPL/AGPL/LGPL-only) code in the distributed
jar tree**. A small number of components are **weak-copyleft** and are
distributed as **unmodified library jars**, which their licenses permit
alongside Apache-2.0 code:

- **Logback** (`logback-classic`, `logback-core`) is dual-licensed
  EPL-1.0 / LGPL-2.1; Wyrdsekai elects **EPL-1.0**.
- **Eclipse Paho MQTT** (`org.eclipse.paho.client.mqttv3`) is **EPL-2.0**.
- The **Jakarta API jars** (servlet, annotation, interceptor, transaction)
  are dual EPL-2.0 / GPL-2.0-with-classpath-exception; Wyrdsekai elects
  **EPL-2.0**.
- A cluster of legacy **CDDL** jars (Jersey 1.x, JAXB, `stax-api`,
  `jsp-api`, `jsr311-api`, `activation`) arrives transitively via
  `hadoop-common` — see the scan below.

Where a component is dual- or triple-licensed, the license Wyrdsekai elects
is stated in the per-component table. Jetty 12 is dual EPL-2.0 / Apache-2.0
and is taken as **Apache-2.0**; JNA and JFFI are taken under their
**Apache-2.0** arm; Jakarta Mail (Angus) and JTS are taken under the
**EDL-1.0 (BSD-3-Clause)** arm.

Upstream `NOTICE` files required by Apache License §4(d) are preserved
verbatim inside each redistributed jar's `META-INF/`; they are not
duplicated here. The largest Apache-project components with NOTICE
obligations are Apache Pekko, Apache Lucene, Apache Hadoop, Apache POI,
Apache PDFBox, Apache Parquet, Apache Avro, Apache MINA SSHD, Apache
HttpComponents, and Apache Commons.

## The `hadoop-common` transitive tree (scan result, 2026-07-21)

`org.apache.hadoop:hadoop-common:3.4.1` is a direct dependency of `:core`,
present only because `parquet-avro` needs Hadoop's `Configuration` class to
read local Parquet files (no HDFS is used). The build already excludes
curator, zookeeper, kerby, protobuf, jetty (Hadoop's copy), javax.servlet,
and netty. What remains of its transitive tree is Apache-2.0 **except** the
legacy CDDL cluster:

| Via hadoop-common | License |
|---|---|
| `com.sun.jersey:jersey-core/-server/-servlet` 1.19.4 | CDDL-1.1 (elected over GPL-2.0-CPE) |
| `com.github.pjfanning:jersey-json` 1.22.0 | CDDL-1.1 (elected) |
| `com.sun.xml.bind:jaxb-impl` 2.2.3-1, `javax.xml.bind:jaxb-api` 2.2.2 | CDDL-1.1 (elected) |
| `javax.annotation:javax.annotation-api` 1.3.2 | CDDL-1.1 (elected) |
| `javax.servlet.jsp:jsp-api` 2.1 | CDDL-1.0 (POM declares none; Sun/GlassFish CDDL) |
| `javax.ws.rs:jsr311-api` 1.1.1 | CDDL-1.0 |
| `javax.xml.stream:stax-api` 1.0-2 | CDDL-1.0 (elected over GPL) |
| `javax.activation:activation` 1.1 | CDDL-1.0 |

CDDL is a file-level weak copyleft; distributing these as unmodified jars
in an Apache-2.0 aggregate is permitted and does not affect Wyrdsekai's
license. None of this cluster is on any Wyrdsekai code path (it backs
Hadoop's HTTP/servlet endpoints, which are never started) — excluding it
from the build is a candidate cleanup, tracked post-OSS.

## Binaries and models bundled by the installers

The .deb/.pkg/.msi installers and the all-in-one Docker image additionally
bundle, unmodified:

| Component | What | License |
|---|---|---|
| [nats-server](https://github.com/nats-io/nats-server) | messaging backbone binary | Apache-2.0 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` + `libggml*` | CPU inference binary | MIT |
| [metasearch2](https://github.com/mat-1/metasearch2) | fallback web-search binary | CC0-1.0 |
| [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) (ONNX q8) | embedding model + tokenizer | Apache-2.0 |
| Wyrdsekai companion models (9B/4B) | inference weights | Wyrdsekai's own releases (see wyrdsekai.org/models) |

**SearXNG is not distributed.** `wyrd setup` can optionally start a SearXNG
container that the user's Docker pulls directly from upstream; SearXNG is
AGPL-3.0 and runs as a separate, unmodified network service.

**OpenJDK** (GPL-2.0 with Classpath Exception) and **GraalVM CE** components
are runtime platforms; the GraalJS/Truffle/polyglot jars that Wyrdsekai does
redistribute are UPL-1.0 (see table).

The React Native and Kotlin Multiplatform mobile clients have their own
dependency trees (predominantly MIT/Apache-2.0, incl. React Native (MIT),
Hermes (MIT), Kotlin/Compose/Ktor (Apache-2.0)); their notices are generated
by the respective app build tooling and shipped inside the app bundles.

## Per-component license summary (server runtime classpath)

| License | Components |
|---|---:|
| Apache-2.0 | 171 |
| BSD-3-Clause | 14 |
| UPL-1.0 | 10 |
| CDDL-1.1 | 7 |
| MIT | 7 |
| EPL-2.0 | 5 |
| BSD-2-Clause | 4 |
| CDDL-1.0 | 4 |
| EDL-1.0 (BSD-3-Clause) | 4 |
| MIT (Bouncy Castle Licence) | 4 |
| UPL-1.0 OR MIT | 3 |
| EPL-1.0 | 2 |
| 0BSD | 1 |
| JDOM License (BSD-style) | 1 |
| MIT-0 | 1 |
| Unicode/ICU | 1 |

| Component | Version | License | Note |
|---|---|---|---|
| `org.tukaani:xz` | 1.10 | 0BSD |  |
| `ai.djl.huggingface:tokenizers` | 0.33.0 | Apache-2.0 |  |
| `ai.djl:api` | 0.33.0 | Apache-2.0 |  |
| `at.favre.lib:bcrypt` | 0.10.2 | Apache-2.0 |  |
| `at.favre.lib:bytes` | 1.5.0 | Apache-2.0 |  |
| `at.yawk.lz4:lz4-java` | 1.10.1 | Apache-2.0 |  |
| `ch.qos.reload4j:reload4j` | 1.2.22 | Apache-2.0 |  |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | Apache-2.0 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-cbor` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson.module:jackson-module-scala_2.13` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.jackson:jackson-bom` | 2.21.1 | Apache-2.0 |  |
| `com.fasterxml.woodstox:woodstox-core` | 5.4.0 | Apache-2.0 |  |
| `com.github.jnr:jffi` | 1.3.13 | Apache-2.0 | dual Apache-2.0/LGPL-3.0; Apache-2.0 elected |
| `com.github.jnr:jnr-a64asm` | 1.0.0 | Apache-2.0 |  |
| `com.github.jnr:jnr-constants` | 0.10.4 | Apache-2.0 |  |
| `com.github.jnr:jnr-ffi` | 2.2.17 | Apache-2.0 |  |
| `com.github.stephenc.jcip:jcip-annotations` | 1.0-1 | Apache-2.0 |  |
| `com.google.android:annotations` | 4.1.1.4 | Apache-2.0 |  |
| `com.google.api.grpc:proto-google-common-protos` | 2.48.0 | Apache-2.0 |  |
| `com.google.code.findbugs:jsr305` | 3.0.2 | Apache-2.0 |  |
| `com.google.code.gson:gson` | 2.13.1 | Apache-2.0 |  |
| `com.google.errorprone:error_prone_annotations` | 2.38.0 | Apache-2.0 |  |
| `com.google.guava:failureaccess` | 1.0.2 | Apache-2.0 |  |
| `com.google.guava:guava` | 33.3.1-android | Apache-2.0 |  |
| `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |  |
| `com.google.http-client:google-http-client-apache-v2` | 1.46.1 | Apache-2.0 |  |
| `com.google.http-client:google-http-client-bom` | 1.46.1 | Apache-2.0 |  |
| `com.google.http-client:google-http-client-gson` | 1.46.1 | Apache-2.0 |  |
| `com.google.http-client:google-http-client` | 1.46.1 | Apache-2.0 |  |
| `com.google.j2objc:j2objc-annotations` | 3.0.0 | Apache-2.0 |  |
| `com.google.oauth-client:google-oauth-client-bom` | 1.37.0 | Apache-2.0 |  |
| `com.google.oauth-client:google-oauth-client-java6` | 1.37.0 | Apache-2.0 |  |
| `com.google.oauth-client:google-oauth-client-jetty` | 1.37.0 | Apache-2.0 |  |
| `com.google.oauth-client:google-oauth-client` | 1.37.0 | Apache-2.0 |  |
| `com.google.zxing:core` | 3.5.3 | Apache-2.0 |  |
| `com.hierynomus:asn-one` | 0.6.0 | Apache-2.0 |  |
| `com.nimbusds:nimbus-jose-jwt` | 9.37.2 | Apache-2.0 |  |
| `com.rometools:rome-utils` | 2.1.0 | Apache-2.0 |  |
| `com.rometools:rome` | 2.1.0 | Apache-2.0 |  |
| `com.typesafe:config` | 1.4.5 | Apache-2.0 |  |
| `com.typesafe:ssl-config-core_2.13` | 0.7.1 | Apache-2.0 |  |
| `com.zaxxer:HikariCP` | 4.0.3 | Apache-2.0 |  |
| `com.zaxxer:SparseBitSet` | 1.3 | Apache-2.0 |  |
| `commons-beanutils:commons-beanutils` | 1.9.4 | Apache-2.0 |  |
| `commons-cli:commons-cli` | 1.5.0 | Apache-2.0 |  |
| `commons-codec:commons-codec` | 1.20.0 | Apache-2.0 |  |
| `commons-collections:commons-collections` | 3.2.2 | Apache-2.0 |  |
| `commons-io:commons-io` | 2.21.0 | Apache-2.0 |  |
| `commons-logging:commons-logging` | 1.3.5 | Apache-2.0 |  |
| `commons-net:commons-net` | 3.9.0 | Apache-2.0 |  |
| `commons-pool:commons-pool` | 1.6 | Apache-2.0 |  |
| `dev.sigstore:sigstore-java` | 1.3.0 | Apache-2.0 |  |
| `io.airlift:aircompressor` | 2.0.2 | Apache-2.0 |  |
| `io.dropwizard.metrics:metrics-core` | 3.2.4 | Apache-2.0 |  |
| `io.github.erdtman:java-json-canonicalization` | 1.1 | Apache-2.0 |  |
| `io.grpc:grpc-api` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-bom` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-context` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-core` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-netty-shaded` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-protobuf-lite` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-protobuf` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-stub` | 1.70.0 | Apache-2.0 |  |
| `io.grpc:grpc-util` | 1.70.0 | Apache-2.0 |  |
| `io.javalin:javalin` | 7.1.0 | Apache-2.0 |  |
| `io.nats:jnats` | 2.25.2 | Apache-2.0 |  |
| `io.opencensus:opencensus-api` | 0.31.1 | Apache-2.0 |  |
| `io.opencensus:opencensus-contrib-http-util` | 0.31.1 | Apache-2.0 |  |
| `io.perfmark:perfmark-api` | 0.27.0 | Apache-2.0 |  |
| `jakarta.enterprise:jakarta.enterprise.cdi-api` | 4.0.1 | Apache-2.0 |  |
| `jakarta.enterprise:jakarta.enterprise.lang-model` | 4.0.1 | Apache-2.0 |  |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 |  |
| `net.java.dev.jna:jna` | 5.14.0 | Apache-2.0 | dual LGPL-2.1/Apache-2.0; Apache-2.0 elected |
| `org.agrona:agrona` | 1.22.0 | Apache-2.0 |  |
| `org.apache.avro:avro` | 1.11.5 | Apache-2.0 |  |
| `org.apache.commons:commons-collections4` | 4.5.0 | Apache-2.0 |  |
| `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 |  |
| `org.apache.commons:commons-configuration2` | 2.10.1 | Apache-2.0 |  |
| `org.apache.commons:commons-lang3` | 3.18.0 | Apache-2.0 |  |
| `org.apache.commons:commons-math3` | 3.6.1 | Apache-2.0 |  |
| `org.apache.commons:commons-text` | 1.11.0 | Apache-2.0 |  |
| `org.apache.hadoop.thirdparty:hadoop-shaded-guava` | 1.3.0 | Apache-2.0 |  |
| `org.apache.hadoop.thirdparty:hadoop-shaded-protobuf_3_25` | 1.3.0 | Apache-2.0 |  |
| `org.apache.hadoop:hadoop-annotations` | 3.4.1 | Apache-2.0 |  |
| `org.apache.hadoop:hadoop-auth` | 3.4.1 | Apache-2.0 |  |
| `org.apache.hadoop:hadoop-common` | 3.4.1 | Apache-2.0 |  |
| `org.apache.httpcomponents:httpclient` | 4.5.14 | Apache-2.0 |  |
| `org.apache.httpcomponents:httpcore` | 4.4.16 | Apache-2.0 |  |
| `org.apache.logging.log4j:log4j-api` | 2.24.3 | Apache-2.0 |  |
| `org.apache.lucene:lucene-analysis-common` | 10.4.0 | Apache-2.0 |  |
| `org.apache.lucene:lucene-core` | 10.4.0 | Apache-2.0 |  |
| `org.apache.lucene:lucene-facet` | 10.4.0 | Apache-2.0 |  |
| `org.apache.lucene:lucene-queries` | 10.4.0 | Apache-2.0 |  |
| `org.apache.lucene:lucene-queryparser` | 10.4.0 | Apache-2.0 |  |
| `org.apache.lucene:lucene-sandbox` | 10.4.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-avro` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-column` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-common` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-encoding` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-format-structures` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-hadoop` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-jackson` | 1.17.0 | Apache-2.0 |  |
| `org.apache.parquet:parquet-variant` | 1.17.0 | Apache-2.0 |  |
| `org.apache.pdfbox:fontbox` | 3.0.7 | Apache-2.0 |  |
| `org.apache.pdfbox:pdfbox-io` | 3.0.7 | Apache-2.0 |  |
| `org.apache.pdfbox:pdfbox` | 3.0.7 | Apache-2.0 |  |
| `org.apache.pekko:pekko-actor-typed_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-actor_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-cluster-sharding-typed_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-cluster-sharding_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-cluster-tools_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-cluster-typed_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-cluster_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-coordination_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-distributed-data_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-persistence-jdbc_2.13` | 1.2.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-persistence-query_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-persistence-typed_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-persistence_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-pki_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-protobuf-v3_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-remote_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-serialization-jackson_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-slf4j_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-stream-typed_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.pekko:pekko-stream_2.13` | 1.4.0 | Apache-2.0 |  |
| `org.apache.poi:poi-ooxml-lite` | 5.5.1 | Apache-2.0 |  |
| `org.apache.poi:poi-ooxml` | 5.5.1 | Apache-2.0 |  |
| `org.apache.poi:poi` | 5.5.1 | Apache-2.0 |  |
| `org.apache.sshd:sshd-common` | 2.17.1 | Apache-2.0 |  |
| `org.apache.sshd:sshd-core` | 2.17.1 | Apache-2.0 |  |
| `org.apache.xmlbeans:xmlbeans` | 5.3.0 | Apache-2.0 |  |
| `org.codehaus.jettison:jettison` | 1.5.4 | Apache-2.0 |  |
| `org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jetty-server` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-servlet` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee10:jetty-ee10-annotations` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee10:jetty-ee10-plus` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee10:jetty-ee10-servlet` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee10:jetty-ee10-webapp` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.ee:jetty-ee-webapp` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.websocket:jetty-websocket-core-common` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.websocket:jetty-websocket-core-server` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.websocket:jetty-websocket-jetty-api` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.websocket:jetty-websocket-jetty-common` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty.websocket:jetty-websocket-jetty-server` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-annotations` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-http` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-io` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-jndi` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-plus` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-security` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-server` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-session` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-util` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.eclipse.jetty:jetty-xml` | 12.1.6 | Apache-2.0 | dual EPL-2.0/Apache-2.0; Apache-2.0 elected |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache-2.0 |  |
| `org.jetbrains:annotations` | 13.0 | Apache-2.0 |  |
| `org.jmdns:jmdns` | 3.6.3 | Apache-2.0 |  |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 |  |
| `org.lmdbjava:lmdbjava` | 0.9.1 | Apache-2.0 |  |
| `org.scala-lang:scala-library` | 2.13.18 | Apache-2.0 |  |
| `org.scala-lang:scala-reflect` | 2.13.17 | Apache-2.0 |  |
| `org.slf4j:jcl-over-slf4j` | 1.7.36 | Apache-2.0 |  |
| `org.xerial.snappy:snappy-java` | 1.1.10.7 | Apache-2.0 |  |
| `org.xerial:sqlite-jdbc` | 3.51.2.0 | Apache-2.0 |  |
| `org.yaml:snakeyaml` | 2.5 | Apache-2.0 |  |
| `com.github.luben:zstd-jni` | 1.5.7-3 | BSD-2-Clause |  |
| `com.typesafe.slick:slick-hikaricp_2.13` | 3.5.1 | BSD-2-Clause |  |
| `com.typesafe.slick:slick_2.13` | 3.5.1 | BSD-2-Clause |  |
| `org.postgresql:postgresql` | 42.7.10 | BSD-2-Clause |  |
| `com.github.virtuald:curvesapi` | 1.08 | BSD-3-Clause |  |
| `com.google.protobuf:protobuf-bom` | 4.29.3 | BSD-3-Clause |  |
| `com.google.protobuf:protobuf-java-util` | 4.29.3 | BSD-3-Clause |  |
| `com.google.protobuf:protobuf-java` | 4.29.3 | BSD-3-Clause |  |
| `com.google.re2j:re2j` | 1.1 | BSD-3-Clause |  |
| `com.jcraft:jsch` | 0.1.55 | BSD-3-Clause |  |
| `com.thoughtworks.paranamer:paranamer` | 2.8.3 | BSD-3-Clause |  |
| `dnsjava:dnsjava` | 3.6.1 | BSD-3-Clause |  |
| `org.codehaus.woodstox:stax2-api` | 4.2.1 | BSD-3-Clause |  |
| `org.ow2.asm:asm-analysis` | 9.7.1 | BSD-3-Clause |  |
| `org.ow2.asm:asm-commons` | 9.9.1 | BSD-3-Clause |  |
| `org.ow2.asm:asm-tree` | 9.9.1 | BSD-3-Clause |  |
| `org.ow2.asm:asm-util` | 9.7.1 | BSD-3-Clause |  |
| `org.ow2.asm:asm` | 9.9.1 | BSD-3-Clause |  |
| `javax.activation:activation` | 1.1 | CDDL-1.0 |  |
| `javax.servlet.jsp:jsp-api` | 2.1 | CDDL-1.0 | POM declares no license; javax.servlet.jsp:jsp-api is CDDL-1.0 (Sun/GlassFish) |
| `javax.ws.rs:jsr311-api` | 1.1.1 | CDDL-1.0 |  |
| `javax.xml.stream:stax-api` | 1.0-2 | CDDL-1.0 | dual CDDL-1.0/GPL; CDDL-1.0 elected |
| `com.github.pjfanning:jersey-json` | 1.22.0 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `com.sun.jersey:jersey-core` | 1.19.4 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `com.sun.jersey:jersey-server` | 1.19.4 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `com.sun.jersey:jersey-servlet` | 1.19.4 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `com.sun.xml.bind:jaxb-impl` | 2.2.3-1 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `javax.annotation:javax.annotation-api` | 1.3.2 | CDDL-1.1 | dual CDDL/GPL-2.0-with-classpath-exception; CDDL elected |
| `javax.xml.bind:jaxb-api` | 2.2.2 | CDDL-1.1 | dual CDDL-1.1/GPL-2.0-with-classpath-exception; CDDL-1.1 elected |
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | EDL-1.0 (BSD-3-Clause) |  |
| `org.eclipse.angus:angus-activation` | 2.0.3 | EDL-1.0 (BSD-3-Clause) |  |
| `org.eclipse.angus:jakarta.mail` | 2.0.5 | EDL-1.0 (BSD-3-Clause) | triple-licensed; EDL-1.0 elected |
| `org.locationtech.jts:jts-core` | 1.20.0 | EDL-1.0 (BSD-3-Clause) | dual EPL-2.0/EDL-1.0; EDL-1.0 elected |
| `ch.qos.logback:logback-classic` | 1.5.16 | EPL-1.0 | dual EPL-1.0/LGPL-2.1; EPL-1.0 elected |
| `ch.qos.logback:logback-core` | 1.5.16 | EPL-1.0 | dual EPL-1.0/LGPL-2.1; EPL-1.0 elected |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL-2.0 | dual EPL-2.0/GPL-2.0-with-classpath-exception; EPL-2.0 elected |
| `jakarta.interceptor:jakarta.interceptor-api` | 2.1.0 | EPL-2.0 | dual EPL-2.0/GPL-2.0-with-classpath-exception; EPL-2.0 elected |
| `jakarta.servlet:jakarta.servlet-api` | 6.0.0 | EPL-2.0 | dual EPL-2.0/GPL-2.0-with-classpath-exception; EPL-2.0 elected |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | EPL-2.0 | dual EPL-2.0/GPL-2.0-with-classpath-exception; EPL-2.0 elected |
| `org.eclipse.paho:org.eclipse.paho.client.mqttv3` | 1.2.5 | EPL-2.0 |  |
| `org.jdom:jdom2` | 2.0.6.1 | JDOM License (BSD-style) |  |
| `com.github.jnr:jnr-x86asm` | 1.0.2 | MIT |  |
| `com.microsoft.onnxruntime:onnxruntime` | 1.23.2 | MIT |  |
| `com.stripe:stripe-java` | 31.4.1 | MIT |  |
| `org.checkerframework:checker-qual` | 3.52.0 | MIT |  |
| `org.codehaus.mojo:animal-sniffer-annotations` | 1.24 | MIT |  |
| `org.slf4j:slf4j-api` | 2.0.17 | MIT |  |
| `org.slf4j:slf4j-reload4j` | 1.7.36 | MIT |  |
| `org.bouncycastle:bcpkix-jdk18on` | 1.80 | MIT (Bouncy Castle Licence) |  |
| `org.bouncycastle:bcprov-jdk18on` | 1.80 | MIT (Bouncy Castle Licence) |  |
| `org.bouncycastle:bcprov-lts8on` | 2.73.10 | MIT (Bouncy Castle Licence) |  |
| `org.bouncycastle:bcutil-jdk18on` | 1.80 | MIT (Bouncy Castle Licence) |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 |  |
| `org.graalvm.polyglot:polyglot` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.regex:regex` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.sdk:collections` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.sdk:jniutils` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.sdk:nativeimage` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.sdk:word` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.shadowed:xz` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.truffle:truffle-api` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.truffle:truffle-compiler` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.truffle:truffle-runtime` | 25.0.2 | UPL-1.0 |  |
| `org.graalvm.js:js-language` | 25.0.2 | UPL-1.0 OR MIT |  |
| `org.graalvm.js:js` | 25.0.2 | UPL-1.0 OR MIT |  |
| `org.graalvm.polyglot:js` | 25.0.2 | UPL-1.0 OR MIT |  |
| `org.graalvm.shadowed:icu4j` | 25.0.2 | Unicode/ICU |  |
| `org.jline:jline` | 4.0.4 | BSD-3-Clause | :cli tree only |
| `net.i2p.crypto:eddsa` | 0.3.0 | CC0-1.0 | :rendezvous / :clients:daemon-desktop trees only |
