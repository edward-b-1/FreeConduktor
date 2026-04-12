# FreeConduktor

All of this was vibe-coded with Claude (Sonnet 4.6). All the features which I have tested, work. Please submit bug reports with screenshots via Github Issues if you test something and find it does not work.

Feature improvements will also be accepted and are encoraged. I would also like to improve the branding and logos.

#### Free Conduktor (Open Source clone of Conduktor Desktop)

An open-source Kafka management desktop GUI built with Kotlin and JavaFX, inspired by Conduktor Desktop. Provides a visual interface for managing Kafka clusters, topics, consumer groups, schemas, connectors, and more.

## Features

- **Cluster management** — save multiple cluster configurations, connect with a single click
- **Overview dashboard** — at-a-glance health metrics for brokers, topics, consumers, streams, and security
- **Topics** — browse, search, create, and delete topics; view partition details and configuration
- **Consumer Groups** — inspect group state, member assignments, per-partition lag, and reset offsets
- **Message Browser** — consume messages from any topic with configurable start position (earliest, latest, specific offset, specific timestamp, or consumer group); supports String, JSON, Integer, Long, Double, and Base64 deserializers
- **Producer** — publish messages to any topic with optional headers
- **Schema Registry** — browse subjects and versions, register new schemas (Avro, JSON Schema, Protobuf), manage compatibility settings
- **Kafka Connect** — list connectors and tasks with status, create/edit/pause/resume/restart/delete connectors
- **Security** — view and manage ACLs and client quotas
- **Kafka Streams** — detect running Streams applications and their internal topics
- **Brokers** — view broker details including controller status
- **Light and dark themes** — toggle between Primer Light and Dracula via the Options menu

## Requirements

- JDK 21 or later (tested with Eclipse Temurin 21)

## Running

```bash
./gradlew run        # Linux / macOS
gradlew.bat run      # Windows (Command Prompt)
.\gradlew run        # Windows (PowerShell)
```

## Configuration

Cluster configurations are stored in `~/.freeconductor/clusters.json`. Each cluster supports:

| Field | Description |
|---|---|
| Bootstrap Servers | Comma-separated `host:port` list |
| Security Protocol | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, or `SASL_SSL` |
| SASL Mechanism | e.g. `PLAIN`, `SCRAM-SHA-256` |
| SASL Username / Password | For SASL authentication |
| SSL Truststore Path / Password | For SSL/TLS connections |
| Schema Registry URL | Optional — enables the Schema Registry panel |
| Schema Registry Username / Password | For authenticated Schema Registry |
| Kafka Connect URL | Optional — enables the Kafka Connect panel |

## Architecture

The application is built entirely on the JVM. The UI is written in **Kotlin** using **JavaFX** — Oracle's desktop GUI framework for Java/JVM applications. JavaFX uses a scene graph model where the UI is composed of nodes (controls, layouts, shapes) arranged in a tree. Built-in controls such as `TableView`, `SplitPane`, `BorderPane`, and `ListView` make up the majority of the interface.

Theming is handled by **AtlantaFX**, a modern CSS-based theme library for JavaFX. It provides the Primer Light and Dracula dark themes, and exposes CSS variables (e.g. `-color-fg-default`, `-color-bg-subtle`) so that custom styles adapt automatically when the theme is switched at runtime.

All Kafka operations run on background threads and post results back to the JavaFX application thread via `Platform.runLater`, keeping the UI responsive.

## Tech Stack

| Component | Library |
|---|---|
| Language | Kotlin 2.2 |
| UI framework | JavaFX 21 |
| Theming | AtlantaFX 2.0 (Primer Light / Dracula) |
| Kafka client | Apache Kafka 3.6 |
| HTTP client | OkHttp 4.12 |
| JSON | Jackson with Kotlin module |
| Async | Kotlin Coroutines |
| Build | Gradle (Kotlin DSL) |

## Project Structure

```
src/main/kotlin/com/freeconductor/
├── App.kt                        # Entry point, theme initialisation
├── model/                        # Data classes (ClusterConfig, TopicInfo, etc.)
├── service/                      # Backend logic
│   ├── KafkaAdminService.kt      # Topics, groups, ACLs, quotas, cluster stats
│   ├── KafkaConsumerService.kt   # Message consumption
│   ├── KafkaProducerService.kt   # Message production
│   ├── KafkaConnectService.kt    # Kafka Connect REST API
│   ├── SchemaRegistryService.kt  # Schema Registry REST API
│   └── ClusterConfigService.kt   # Persists cluster configs to ~/.freeconductor/
└── ui/                           # JavaFX views
    ├── MainWindow.kt             # Root layout, toolbar, theme switching
    ├── ClusterSidebar.kt         # Cluster list and navigation
    ├── OverviewView.kt           # Dashboard
    ├── topics/                   # Topics list and detail
    ├── consumergroups/           # Consumer groups and offset reset
    ├── consume/                  # Message browser
    ├── produce/                  # Producer dialog
    ├── schema/                   # Schema Registry
    ├── connect/                  # Kafka Connect
    ├── acl/                      # ACL management
    ├── security/                 # Security panel (ACLs + Quotas)
    ├── streams/                  # Kafka Streams detection
    ├── cluster/                  # Broker view and add-cluster dialog
    └── util/                     # Shared table utilities and alerts
```
