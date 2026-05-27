# JAX-RS Jakarta REST Compliance Plan

## Summary

Build full Jakarta REST 4.0 compliance through an opt-in `micronaut-jaxrs-jakarta` aggregate while keeping the default JAX-RS modules lightweight, compile-time oriented, and reflection-free by default.

Current baseline: `:micronaut-tests:micronaut-jaxrs-tck:test` passes with existing exclusions, reporting `2764` tests, `2069` passed, `0` failures/errors, and `695` skipped. `tests/jaxrs-tck/failingTests.xml` currently tracks `132` known-failing entries: `46` classes and `86` methods.

## Key Changes

- Add modules:
  - `micronaut-jaxrs-reflection`: spec-only reflection fallback for APIs that cannot be satisfied from generated metadata.
  - `micronaut-jaxrs-jakarta`: aggregate compliance dependency including server, client, generated compliance support, and `jaxrs-reflection`; servlet/security adapters remain separate unless a TCK profile requires them.
- Move compliance metadata to build time:
  - Extend `jaxrs-processor` to analyze resources, providers, `Application` subclasses, subresource locators, `@BeanParam`, `@MatrixParam`, `@Encoded`, field/property injection points, and `ParamConverterProvider`s.
  - Use Micronaut SourceGen for generated helper classes instead of handwritten source strings.
  - Generate route/provider/parameter metadata and typed binders so runtime code uses Micronaut beans, introspections, and generated registries instead of reflection or classpath scanning.
- Keep runtime reflection isolated:
  - Remove or avoid reflection in default `jaxrs-common`, `jaxrs-server`, `jaxrs-client`, and `jaxrs-processor` runtime paths.
  - Route unavoidable `Class`, `Method`, provider-class registration, and non-introspected `Application#getClasses()` behavior through `jaxrs-reflection`.
  - Do not add dynamic classloading beyond Jakarta-mandated service provider entry points.
- Rework TCK tracking:
  - Replace ad hoc `exclusions.txt` as the source of truth with `tests/jaxrs-tck/failingTests.xml`.
  - Add Gradle tasks `jakartaTck`, `discoverFailingJakartaTck`, `refreshFailingJakartaTckSuite`, `failingJakartaTck`, and `singleJakartaTck`.
  - Keep current excluded `test` task as the stable baseline until `jakartaTck` reaches zero failures.
- Tackle gaps in waves:
  - Request matching and subresources.
  - Parameter injection: `@MatrixParam`, `@BeanParam`, `@Encoded`, field/property/constructor injection.
  - Context APIs: full `UriInfo`, `Request`, `Providers`, `ContextResolver`, `ResourceInfo`, security/servlet contexts.
  - Providers/interceptors/features/configuration and standard entity readers/writers.
  - Client API compliance, async/Rx invokers, lifecycle, executor, request context mutability.
  - Remaining TCK areas: SSE, multipart, SE bootstrap, signature tests, JAXB/XML binding.
- Micronaut Core changes:
  - Create/use a Core `5.1.x` worktree only after a focused failing TCK or local reproducer proves the need.
  - Expected Core candidates are route matching metadata, raw path/matrix segment access, matched URI/resource tracking, and request mutation hooks.
  - Core changes must be additive, narrow, and verified independently before wiring JAX-RS to them.

## Public APIs And Modules

- New published artifacts: `micronaut-jaxrs-reflection` and `micronaut-jaxrs-jakarta`.
- Any new Core or JAX-RS public API must be additive and use `@since` from the verified target branch version.
- Default modules remain source-compatible; no existing user should need reflection or the aggregate unless they opt into compliance.

## Test Plan

- Baseline: keep current excluded TCK passing.
- Discovery: run full no-exclusion `discoverFailingJakartaTck`, generate `failingTests.xml`, and cluster failures by spec area.
- Iteration: each fix removes entries from `failingTests.xml` in the same commit as implementation.
- Progress tracker: keep a comment near the top of `tests/jaxrs-tck/failingTests.xml` with total, passed, failed, errors, and skipped counts from the latest baseline or discovery run; update it whenever focused TCK work changes the file.
- Commit discipline: once a focused group of TCK/module tests passes, stage only the related implementation, tests, plan updates, and `failingTests.xml` changes, then create a focused commit before moving to the next group so the compliance history stays reviewable.
- Focused proof: use `singleJakartaTck -PtckSingleClass=... -PtckSingleMethod=...` for each TCK cluster.
- Module tests:
  - processor generated metadata/source tests;
  - reflection module fallback tests;
  - aggregate module wiring tests;
  - no-reflection/default-module tests.
- Final verification: `jakartaTck` with no known failures, affected module `check`, `docs`, and `japiCmp` for public API changes.

## Assumptions

- Compliance target is Jakarta REST 4.0, matching the current `jakarta.ws.rs-api 4.0.0` and TCK `4.0.1` configuration.
- `micronaut-jaxrs-jakarta` is the compliance path; default `micronaut-jaxrs-*` modules remain lightweight.
- Runtime reflection is allowed only in `micronaut-jaxrs-reflection` and only for spec requirements that cannot be generated safely.
- Core work starts only after proof, not as a default first step.
