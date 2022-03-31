# Micronaut JAX-RS

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.jaxrs/micronaut-jaxrs-server.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.micronaut.jaxrs%22%20AND%20a:%22micronaut-jaxrs-server%22)
[![Build Status](https://github.com/micronaut-projects/micronaut-jaxrs/workflows/Java%20CI/badge.svg)](https://github.com/micronaut-projects/micronaut-jaxrs/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=micronaut-projects_micronaut-jaxrs&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=micronaut-projects_micronaut-jaxrs)
[![Revved up by Gradle Enterprise](https://img.shields.io/badge/Revved%20up%20by-Gradle%20Enterprise-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.micronaut.io/scans)

Micronaut JAX-RS adds the ability to use common JAX-RS types and annotations to a Micronaut application.

This project is not an implementation of the JAX-RS specification and is designed to allow users familiar with the JAX-RS API to use the most common parts of the API within the context of a Micronaut application. 

## Documentation

See the [Documentation](https://micronaut-projects.github.io/micronaut-jaxrs/latest/guide/) for more information. 

See the [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-jaxrs/snapshot/guide/) for the current development docs.

## Snapshots and Releases

Snaphots are automatically published to [JFrog OSS](https://oss.jfrog.org/artifactory/oss-snapshot-local/) using [Github Actions](https://github.com/micronaut-projects/micronaut-jaxrs/actions).

See the documentation in the [Micronaut Docs](https://docs.micronaut.io/latest/guide/index.html#usingsnapshots) for how to configure your build to use snapshots.

Releases are published to JCenter and Maven Central via [Github Actions](https://github.com/micronaut-projects/micronaut-jaxrs/actions).

A release is performed with the following steps:

- [Edit the version](https://github.com/micronaut-projects/micronaut-jaxrs/edit/master/gradle.properties) specified by `projectVersion` in `gradle.properties` to a semantic, unreleased version. Example `1.0.0`
- [Create a new release](https://github.com/micronaut-projects/micronaut-jaxrs/releases/new). The Git Tag should start with `v`. For example `v1.0.0`.
- [Monitor the Workflow](https://github.com/micronaut-projects/micronaut-jaxrs/actions?query=workflow%3ARelease) to check it passed successfully.
- Celebrate!

