# JAX-RS Jakarta REST Compliance Plan

## Summary

Build full Jakarta REST 4.0 compliance through an opt-in `micronaut-jaxrs-jakarta` aggregate while keeping the default JAX-RS modules lightweight, compile-time oriented, and reflection-free by default.

Current baseline: `:micronaut-tests:micronaut-jaxrs-tck:test` passes with existing exclusions, reporting `2803` tests, `2674` passed, `0` failures/errors, and `129` skipped. `tests/jaxrs-tck/failingTests.xml` currently tracks `1` Netty-baseline exclusion: `1` servlet-runtime method. The servlet method is covered by `:micronaut-tests:micronaut-jaxrs-tck:jakartaServletTck`, which runs `setPropertyIsReflectedInServletRequestTest` against Micronaut Servlet/Jetty.

## Key Changes

- Add modules and reuse existing integrations:
  - `micronaut-jaxrs-reflection`: spec-only reflection fallback for APIs that cannot be satisfied from generated metadata.
  - `micronaut-jaxrs-xml`: optional XML, Activation `DataSource`, `Source`, `JAXBElement`, and JAXB entity provider support. Default `jaxrs-common`, `jaxrs-server`, and `jaxrs-client` must not expose or require JAXB/XML dependencies; the compliance aggregate can include this module when the TCK requires standard XML providers.
    - Current server dependency audit: `micronaut-jaxrs-server` must have no `jakarta.xml.bind` or `jakarta.activation` dependencies on `compileClasspath`, `runtimeClasspath`, or `testRuntimeClasspath`; XML provider tests belong in `micronaut-jaxrs-xml`, not the server module.
    - Verified audit: `jaxrs-server`, `jaxrs-common`, and `jaxrs-client` have no `jakarta.xml.bind`, `javax.xml.bind`, `JAXB*`, `jakarta.activation`, or `javax.activation` source references outside `jaxrs-xml`; server references to XML are limited to media-type selection tests and do not require JAXB APIs. Gradle dependency insight for `micronaut-jaxrs-server` reports no `jakarta.xml.bind`, broad `jaxb`, or `jakarta.activation` matches on `compileClasspath`, `runtimeClasspath`, or `testRuntimeClasspath`.
  - `micronaut-jaxrs-jakarta`: aggregate compliance dependency including server, client, generated compliance support, and `jaxrs-reflection`; security adapters remain separate unless a TCK profile requires them.
    - Current XML/JAXB aggregate audit: `micronaut-jaxrs-jakarta` currently includes `micronaut-jaxrs-xml`, which brings `jakarta.xml.bind-api` and `jakarta.activation-api` through the aggregate runtime classpath. This is not a `micronaut-jaxrs-server` dependency, but it remains the explicit compliance-path tradeoff unless XML/JAXB is made opt-in even for the aggregate artifact.
    - Current JSON-B optionality: JSON-B and Jakarta JSON APIs are `compileOnly` for `micronaut-jaxrs-jakarta` and are emitted as optional Maven dependencies; the JSON-B reader/writer bean is guarded by `@Requires(classes = Jsonb.class)`, and the service-loaded client customizer is verified to load without registering JSON-B providers when JSON-B is absent from the runtime classpath.
  - Requirement: do not add a new JAX-RS servlet implementation module for compliance. Reuse `micronaut-servlet` APIs, binders, and servlet-engine implementations for `ServletRequest`, `HttpServletRequest`, `ServletContext`, and related servlet context objects, adding only thin JAX-RS glue where existing Micronaut Servlet support cannot be reused directly.
- Move compliance metadata to build time:
  - Extend `jaxrs-processor` to analyze resources, providers, `Application` subclasses, subresource locators, `@BeanParam`, `@MatrixParam`, `@Encoded`, field/property injection points, and `ParamConverterProvider`s.
  - Requirement: keep `JaxRsTypeElementVisitor` and related `TypeElementVisitor` code paths language-neutral. Use Micronaut AST, `AnnotationValue`, and SourceGen APIs for annotation values, paths, generated metadata, and generated code; do not use language/compiler-native constructs such as `nativeStringValue`, `getNativeType`, `javax.lang.model` elements, or Java annotation mirrors in these visitors.
  - Requirement: keep `JaxRsApplicationProcessor` as a regular Micronaut `TypeElementVisitor` and override `getSupportedAnnotationNames()` with the narrowest practical annotation set, currently `ApplicationPath`, `Path`, `Provider`, and `Context`, so the visitor remains isolating and does not run as an all-elements processor.
    - Current processor guard: processor main sources and visitor service metadata have no `javax.lang.model`, APT, `nativeStringValue`, `getNativeType`, or Java annotation mirror usage; the focused `JaxRsApplicationProcessorSpec` verifies the supported annotation names remain isolated to the JAX-RS application/resource/provider/context annotations.
  - Keep unsupported Jakarta REST compile-time failures enabled by default, but gate them behind an explicit processor option so the TCK can disable the failures while exercising compliance fallbacks. The TCK archive compiler may also disable Micronaut route validation for Jakarta REST subresource templates that are valid for the spec but cannot be represented as a Micronaut route method signature without synthetic parameters.
    - Current failure-gate progress: unsupported failures default to enabled through `micronaut.jaxrs.fail.on.unsupported=true`; the TCK archive compiler passes `-Amicronaut.jaxrs.fail.on.unsupported=false` and `-Amicronaut.route.validation=false`; focused processor tests prove default `@BeanParam`/request-field failures still fail and the disabled path maps `@BeanParam` to `@RequestBean` with generated introspection metadata.
  - Requirement: implement `@BeanParam` by mapping it to Micronaut Core `@RequestBean`; because `@RequestBean` binding is introspection-backed, generate the needed `BeanIntrospection` metadata first and use reflection fallback only after a focused TCK case proves an injection shape cannot be represented through `@RequestBean` metadata.
    - Current `@BeanParam` progress: the processor annotates `@BeanParam` parameters and nested bean-param fields with `@RequestBean`, generates SourceGen helper classes that apply `@Introspected(accessKind = FIELD/METHOD, visibility = ANY)` to the bean-param types, and focused processor tests assert both the `@RequestBean` mapping and generated introspection source. The reflection fallback remains isolated in `micronaut-jaxrs-reflection` through `JaxRsRequestBeanAnnotationBinder`, with its own focused field-injection fallback test.
  - Requirement: reuse Micronaut multipart APIs for Jakarta REST multipart binding. Server-side `@FormParam` and entity-part binding should use Micronaut's form/multipart request infrastructure such as `FormCapableHttpRequest`, `FormRouteCompleter`, `FormFactory`, and server `MultipartBody`/`CompletedPart` streams where practical; client-side `EntityPart` requests should continue to adapt to Micronaut's client `MultipartBody.Builder`. Keep manual multipart parsing/writing isolated to generic JAX-RS `MessageBodyReader`/`MessageBodyWriter` fallback paths only when the core multipart APIs cannot be used directly.
    - Current multipart progress: server `@FormParam` multipart binding uses `FormRouteCompleter` and `FormFactory.completePart` to collect all matching Micronaut `RawFormField`s before binding string arrays/lists, numeric arrays/lists, `byte[]`, `InputStream`, single `EntityPart`, and filtered `List<EntityPart>` values. Unannotated `List<EntityPart>` entity parameters continue to use `FormCapableHttpRequest.getRawFormFields()` plus `FormFactory.completePart`, and focused Netty tests send Micronaut client `MultipartBody` requests through both paths.
  - Requirement: keep build-time metadata authoritative where practical, including constants in `jaxrs-common` for generated annotation member names such as `literalCharacters`, subresource-locator target media metadata, and precomputed route matching/filter predicates so the processor and runtime share names and request-time filtering is only applied to relevant JAX-RS routes instead of every route.
    - Current constants progress: route score metadata (`literalCharacters`, capturing-group counts, root-template scores), subresource locator target metadata, and generated binding default values are represented as shared `jaxrs-common` constants consumed by both processor and runtime code.
    - Current filter progress: `JaxRsFilters` precomputes the application JAX-RS route set, indexes Accept-negotiation candidates by HTTP method, caches route scores from generated metadata, and skips the negotiation candidate scan when no explicit `Accept` header is present. Post-matching request/response filters still use the matched `RouteInfo` guard so non-JAX-RS routes return before Jakarta filter state is created.
  - Requirement: keep `JaxRsContainerMessageBodyHandlerRegistry` selection data as build-time metadata where possible, including provider runtime constraints, consumed/produced media types, and reader/writer entity types, so runtime lookup prunes candidates before bean instantiation and only falls back to dynamic `isReadable`/`isWriteable` checks for providers that remain plausible matches.
    - Current provider metadata progress: reader/writer runtime constraints, consumed/produced media types, entity types, and type-variable flags are stored in `JaxRsMessageBodyProvider` metadata. The container registry builds lookup candidates from bean definitions, caches type/media lookup keys, prunes by media/type/runtime before bean lookup, and focused tests prove wrong-media, wrong-type, and client-only providers are not instantiated for an unrelated lookup.
  - Requirement: precompute or cache repeated runtime decisions such as subresource target HTTP methods, path segment scores, and fixed no-content placeholders; prefer table-driven lookups over repeated request-time `if`/`else` chains.
    - Current routing progress: `JaxRsRouterListener` and `JaxRsSubResourceLocatorWriter` share a resolved `JaxRsRequestMethod`, so request method overrides and HEAD-to-GET matching are computed once per routing/subresource selection path instead of repeatedly parsing request state. Router route metadata also precomputes each JAX-RS route HTTP method name, target-method sets, route scores, and media arrays at router creation, and root-resource scoring reuses the same stripped/application-relative lookup URI list while selecting matches for a request.
    - Current no-content progress: fixed no-content placeholders are resolved through a static `Map<Class<?>, Supplier<?>>` in the entity binder, with optional module-specific placeholders such as `JAXBElement` supplied through guarded provider beans only after the table lookup misses.
  - Requirement: keep `jaxrs-server/src/main/java/io/micronaut/jaxrs/container/JaxRsRouterListener.java` lightweight. Push path/resource matching decisions, literal-character counts, dynamic subresource metadata, and filter applicability into visitor-generated metadata; avoid complex splitting, concatenation, or repeated per-route predicate evaluation on the request hot path unless a focused TCK case proves it cannot be precomputed.
  - Use Micronaut SourceGen for generated helper classes instead of handwritten source strings.
  - Generate route/provider/parameter metadata and typed binders so runtime code uses Micronaut beans, introspections, and generated registries instead of reflection or classpath scanning.
- Keep runtime reflection isolated:
  - Remove or avoid reflection in default `jaxrs-common`, `jaxrs-server`, `jaxrs-client`, and `jaxrs-processor` runtime paths.
  - Route unavoidable `Class`, `Method`, provider-class registration, and non-introspected `Application#getClasses()` behavior through `jaxrs-reflection`.
  - Do not add dynamic classloading beyond Jakarta-mandated service provider entry points.
  - Current reflection audit: `JaxRsConfiguration` no longer imports or directly uses `java.lang.reflect.Method`, `Field`, or `Constructor`; class-registered client components use Micronaut `BeanIntrospection`/`BeanMethod`/`BeanWriteProperty` for construction and `@Context` injection, and the ServiceLoader-backed reflective fallback lives in `micronaut-jaxrs-reflection`. Default-module `Method` references that remain are Jakarta API-shaped signatures such as `UriBuilder.path(Method)` and `ResourceInfo#getResourceMethod()`, not reflective invocation or injection paths.
- Rework TCK tracking:
  - Replace ad hoc `exclusions.txt` as the source of truth with `tests/jaxrs-tck/failingTests.xml`.
  - Add Gradle tasks `jakartaTck`, `jakartaNettyNoExclusionTck`, `discoverFailingJakartaTck`, `refreshFailingJakartaTckSuite`, `failingJakartaTck`, and `singleJakartaTck`.
  - Add a separate servlet TCK runtime classpath and tasks `jakartaServletTck` / `singleJakartaServletTck` for TCK methods that require servlet request objects. This profile reuses Micronaut Servlet/Jetty and excludes the Netty server artifact without changing the default TCK baseline.
  - Keep current excluded `test` task as the stable Netty baseline and make `jakartaTck` the cross-runtime compliance aggregate: Netty baseline plus the servlet-only TCK profile. Keep `jakartaNettyNoExclusionTck` and `discoverFailingJakartaTck` as diagnostics for finding Netty-profile gaps.
  - Guard `jakartaTck` so the tracked Netty exclusions remain limited to servlet-only methods that are explicitly covered by `jakartaServletTck`; any new class-level or non-servlet known failure must fail the aggregate until it is fixed or assigned to an appropriate runtime proof.
- Tackle gaps in waves:
  - Request matching and subresources.
  - Parameter injection: `@MatrixParam`, `@BeanParam`, `@Encoded`, field/property/constructor injection.
  - Context APIs: full `UriInfo`, `Request`, `Providers`, `ContextResolver`, `ResourceInfo`, security/servlet contexts.
  - Providers/interceptors/features/configuration and standard entity readers/writers.
  - Client API compliance, async/Rx invokers, lifecycle, executor, request context mutability.
    - Current client lifecycle progress: `ClientBuilder#scheduledExecutorService` is preserved in copied client configuration, SSE event sources created for Micronaut JAX-RS targets use an owned `SseClient` so `SseEventSource.close()` tears down the active SSE connection promptly, client close cascades to registered event sources, externally supplied schedulers are not shut down by event-source close, and unopened event sources close without waiting for the timeout.
  - Recently verified TCK areas: SSE lifecycle and data conversion, multipart binding, SE bootstrap, signature tests, and JAXB/XML binding through the optional XML module.
- Micronaut Core changes:
  - Create/use a Core `5.1.x` worktree only after a focused failing TCK or local reproducer proves the need.
  - Expected Core candidates are route matching metadata, raw path/matrix segment access, matched URI/resource tracking, and request mutation hooks.
  - Core changes must be additive, narrow, and verified independently before wiring JAX-RS to them.

## Public APIs And Modules

- New published artifacts: `micronaut-jaxrs-reflection`, `micronaut-jaxrs-xml`, and `micronaut-jaxrs-jakarta`.
- Any new Core or JAX-RS public API must be additive and use `@since` from the verified target branch version.
- Default modules remain source-compatible; no existing user should need reflection or the aggregate unless they opt into compliance.

## Test Plan

- Baseline: keep current excluded TCK passing.
- Discovery: run full Netty no-exclusion `discoverFailingJakartaTck`, generate `failingTests.xml`, and cluster failures by spec area.
- Iteration: each fix removes entries from `failingTests.xml` in the same commit as implementation.
- Progress tracker: keep a comment near the top of `tests/jaxrs-tck/failingTests.xml` with total, passed, failed, errors, and skipped counts from the latest baseline or discovery run; update it whenever focused TCK work changes the file.
- Skipped-test accounting: the latest excluded Netty baseline's `129` skipped tests are TCK/JUnit skipped results, not `failingTests.xml` known-failure exclusions. They break down as `57` async executor tests, `29` executor Rx tests, `39` RxInvoker tests, `1` URI builder test (`replaceQueryTest4`), `1` URI info test (`getNormalizedUriTest`), `1` SSE event-source test (`connectionLostForDefault500msTest`), and the `1` servlet request-context method that is deliberately excluded from the Netty baseline and covered by the servlet TCK profile. A compliance claim must keep these skips visible and explainable; only the servlet method is a tracked product/runtime gap, while the other skipped cases are currently TCK-reported skips rather than failures to add to `failingTests.xml`.
- Commit discipline: once a focused group of TCK/module tests passes, stage only the related implementation, tests, plan updates, and `failingTests.xml` changes, then create a focused commit before moving to the next group so the compliance history stays reviewable.
- Focused proof: use `singleJakartaTck -PtckSingleClass=... -PtckSingleMethod=...` for each TCK cluster.
- Servlet proof: use `jakartaServletTck` for the current servlet request context case and `singleJakartaServletTck -PtckSingleClass=... -PtckSingleMethod=...` for additional servlet-only TCK cases.
- Module tests:
  - processor generated metadata/source tests;
  - reflection module fallback tests;
  - aggregate module wiring tests;
  - no-reflection/default-module tests.
- Dependency guards: verify `:micronaut-jaxrs-server:dependencyInsight --dependency jakarta.xml.bind --configuration compileClasspath`, `runtimeClasspath`, and `testRuntimeClasspath`, repeat for broad `jaxb` and `jakarta.activation`; all server checks must report no matches. Verify optional XML/JAXB provider coverage in `:micronaut-jaxrs-xml:test`, and keep any aggregate `micronaut-jaxrs-jakarta` JAXB/Activation edge documented as an aggregate-only compliance dependency.
- Processor guard: before merging visitor changes, verify `JaxRsTypeElementVisitor` and related `TypeElementVisitor` implementations do not use `nativeStringValue`, `getNativeType`, `javax.lang.model`, Java annotation mirrors, or other language-specific constructs.
- Final verification: `jakartaTck` must pass as the cross-runtime compliance aggregate, `jakartaNettyNoExclusionTck` should have no failures except servlet-only methods covered by `jakartaServletTck`, plus affected module `check`, `docs`, and `japiCmp` for public API changes.

## Assumptions

- Compliance target is Jakarta REST 4.0, matching the current `jakarta.ws.rs-api 4.0.0` and TCK `4.0.1` configuration.
- `micronaut-jaxrs-jakarta` is the compliance path; default `micronaut-jaxrs-*` modules remain lightweight.
- Runtime reflection is allowed only in `micronaut-jaxrs-reflection` and only for spec requirements that cannot be generated safely.
- Core work starts only after proof, not as a default first step.
