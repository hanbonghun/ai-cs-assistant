# AI CS Assistant MVP

## Overview

AI CS Assistant is a Spring Boot service for customer inquiry intake, manual document management, and AI-assisted analysis support. The app exposes REST APIs and a web UI for counseling workflows.

## Tech Stack

- Java 17
- Spring Boot 3.5
- Spring Data JPA
- PostgreSQL
- pgvector
- springdoc-openapi

## Prerequisites

- JDK 21 installed for local execution in this environment
- PostgreSQL with the `pgvector` extension available
- A local database named `aicsassistant`, or matching connection values set through environment variables

## Local Database Requirement

The `local` profile expects a PostgreSQL instance with pgvector enabled. By default, the app connects to:

- `jdbc:postgresql://localhost:5432/aicsassistant`
- username: `postgres`
- password: `postgres`

Override those defaults with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` if needed.

## Run Locally

Use this command to start the app with the local bootstrap data:

```bash
JAVA_HOME=/Users/minishtechq/.asdf/installs/java/openjdk-21 GRADLE_USER_HOME=/Users/minishtechq/.gradle ./gradlew bootRun --args='--spring.profiles.active=local'
```

## API and UI

- Swagger/OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Swagger UI redirect: `http://localhost:8080/swagger-ui.html`

## Demo Data

When the `local` profile starts against an empty database, it seeds demo inquiries and manuals. If either table already contains data, the bootstrap skips seeding so it is safe to rerun.
