# Reusable Spring Boot 4 Error Handler

Reusable Spring Boot 4 auto-configuration that turns exceptions into structured
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) responses and
propagates the Micrometer trace ID to every response.

## Why use this library

Out of the box, Spring Boot returns a plain `500 Internal Server Error` for most
unhandled exceptions, and its default `BasicErrorController` produces a flat JSON
object that varies between Spring versions and reveals internal details such as
exception class names and stack-trace snippets. This makes it hard for API clients
to handle errors programmatically and exposes information that should stay
server-side.

This library replaces that behaviour with a single, consistent error contract across
every failure mode your API can encounter:

- **Structured, machine-readable bodies.** Every error follows RFC 9457 Problem
  Details with a stable `code` field (e.g. `INVALID_ENUM_VALUE`, `VALIDATION_FAILED`,
  `DUPLICATE_VALUE`). Clients can branch on `code` without parsing human-readable
  messages.
- **Precise field-level context.** JSON deserialization errors include the exact
  JSONPath (`$.address.street`), the rejected value, the expected type, and — for
  enums — the full list of valid values. Validation errors list every constraint
  violation in one response so clients do not need to submit the form multiple times
  to discover all problems.
- **No internal leakage.** Raw database messages, exception class names, and stack
  traces never reach the client. Constraint names are extracted from PostgreSQL
  error messages via regex and presented as opaque codes.
- **Consistent tracing.** The Micrometer trace ID is stamped into every error body
  (`traceId`) and onto every HTTP response (`X-Trace-Id` header), so support teams
  can correlate a client-side error report directly to a distributed trace.
- **Drop-in auto-configuration.** Package this module as a JAR and declare it as a
  dependency — no `@Import`, no `@ComponentScan`, no boilerplate. Spring Boot picks
  up the handlers automatically.

## Requirements

| Dependency                                                                                                  | Notes                                                          |
|-------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| Spring Boot 4.x                                                                                             | Tested on 4.0.6                                                |
| `spring-boot-starter-webmvc`                                                                                | Required                                                       |
| `spring-boot-starter-validation`                                                                            | Required for `ValidationExceptionHandler`                      |
| `spring-boot-starter-data-jpa`                                                                              | Required for `DataExceptionHandler`                            |
| `spring-boot-starter-actuator` + `spring-boot-micrometer-tracing-brave` + `micrometer-tracing-bridge-brave` | Optional — enables `traceId` in bodies and `X-Trace-Id` header |

## Adding the library to a project

Copy the `com.example.demo.error` package into your project. Spring Boot's component
scan will pick up all `@RestControllerAdvice` and `@Component` beans automatically,
provided the package is within your application's scan root.

> **Packaging as a reusable JAR.** If you extract these classes into a shared library,
> register them in
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
> (one fully-qualified class name per line). Spring Boot reads that file at startup and
> activates the listed classes as auto-configurations, so consumers of the JAR need no
> `@Import` or `@ComponentScan` — the dependency alone is sufficient.

## What's included

### `JsonExceptionHandler` — 400 Bad Request

Handles `HttpMessageNotReadableException` (Jackson deserialization failures). Maps the
Jackson 3 cause chain to a structured response:

| Cause                             | `code`                   | Extra fields                                          |
|-----------------------------------|--------------------------|-------------------------------------------------------|
| `UnrecognizedPropertyException`   | `UNKNOWN_JSON_FIELD`     | `path`, `validValues`                                 |
| `InvalidFormatException` (enum)   | `INVALID_ENUM_VALUE`     | `path`, `invalidValue`, `expectedType`, `validValues` |
| `InvalidFormatException` (scalar) | `INVALID_FIELD_VALUE`    | `path`, `invalidValue`, `expectedType`                |
| `MismatchedInputException`        | `TYPE_MISMATCH`          | `path`, `expectedType`                                |
| `InputCoercionException`          | `INTEGER_OVERFLOW`       | `validRange`, `line`, `column`                        |
| `StreamReadException`             | `MALFORMED_JSON`         | `line`, `column`                                      |
| Other                             | `MALFORMED_REQUEST_BODY` | —                                                     |

```json
{
  "type": "about:blank",
  "title": "Invalid enum value",
  "status": 400,
  "detail": "Cannot deserialize value 'UNKNOWN' as Category at path $.category — valid values: [ELECTRONICS, BOOKS, CLOTHING]",
  "instance": "/products",
  "code": "INVALID_ENUM_VALUE",
  "path": "$.category",
  "invalidValue": "UNKNOWN",
  "expectedType": "Category",
  "validValues": [
    "ELECTRONICS",
    "BOOKS",
    "CLOTHING"
  ],
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

### `ValidationExceptionHandler` — 422 Unprocessable Content

Handles `MethodArgumentNotValidException` (`@Valid` on `@RequestBody`) and
`HandlerMethodValidationException` (`@Validated` on individual parameters).

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": "One or more fields failed validation",
  "instance": "/products",
  "code": "VALIDATION_FAILED",
  "violations": [
    {
      "path": "$.name",
      "invalidValue": "",
      "message": "must not be blank"
    },
    {
      "path": "$.price",
      "invalidValue": "-1",
      "message": "must be greater than 0"
    }
  ],
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

### `DataExceptionHandler` — 404 / 409

Handles JPA and JDBC data exceptions. Constraint names are extracted from the PostgreSQL
error message via regex; raw database messages are never forwarded to the client.

| Exception                         | Condition              | `code`                            | Status |
|-----------------------------------|------------------------|-----------------------------------|--------|
| `DuplicateKeyException`           | any                    | `DUPLICATE_VALUE`                 | 409    |
| `DataIntegrityViolationException` | unique constraint      | `DUPLICATE_VALUE`                 | 409    |
| `DataIntegrityViolationException` | foreign key constraint | `REFERENTIAL_INTEGRITY_VIOLATION` | 409    |
| `DataIntegrityViolationException` | other                  | `DATA_INTEGRITY_VIOLATION`        | 409    |
| `EmptyResultDataAccessException`  | any                    | `RESOURCE_NOT_FOUND`              | 404    |
| `EntityNotFoundException`         | any                    | `RESOURCE_NOT_FOUND`              | 404    |

### `GlobalExceptionHandler` — Spring MVC infrastructure + catch-all

Extends `ResponseEntityExceptionHandler` to handle the full set of Spring MVC exceptions
(405, 415, 400, 404, 408, 413, …) and adds a stable `code` field to each. A final
`@ExceptionHandler(Exception.class)` returns 500 `INTERNAL_SERVER_ERROR` and logs
the stack trace at `ERROR` level.

### `ProblemDetailTraceAdvice`

`ResponseBodyAdvice` that appends `"traceId": "<hex>"` to every `ProblemDetail` body
produced by an `@ExceptionHandler`. Scoped to exception handlers only — normal 2xx
responses are not intercepted.

### `TraceIdResponseHeaderFilter`

`OncePerRequestFilter` that sets an `X-Trace-Id: <hex>` response header on **every**
response (success and error alike). Runs at `Ordered.LOWEST_PRECEDENCE` so it is
guaranteed to execute inside the `ServerHttpObservationFilter` span context.

## Configuration

### Jackson — unknown field detection

```properties
# Required for JsonExceptionHandler to produce UNKNOWN_JSON_FIELD responses.
# Without this, Jackson silently ignores unrecognised fields and the handler
# never sees an UnrecognizedPropertyException.
spring.jackson.deserialization.fail-on-unknown-properties=true
```

### Tracing

```properties
# Fraction of requests that receive a trace ID (0.0–1.0).
# Defaults to 0.1 (10 %). Set to 1.0 in development so every request
# gets an X-Trace-Id header and a traceId field in error bodies.
management.tracing.sampling.probability=1.0
# Disable tracing entirely without removing the dependency.
# ProblemDetailTraceAdvice and TraceIdResponseHeaderFilter become no-ops.
# management.tracing.enabled=false
```

### Logging

#### Log levels per handler

Each handler logs at a level that reflects the severity of the underlying problem.
Override individual loggers to tune verbosity:

```properties
# JsonExceptionHandler — DEBUG for all recognised causes, WARN for unclassified bodies
logging.level.com.example.demo.error.JsonExceptionHandler=DEBUG
# ValidationExceptionHandler — DEBUG for every violation set
logging.level.com.example.demo.error.ValidationExceptionHandler=DEBUG
# DataExceptionHandler — WARN for constraint violations, DEBUG for not-found
logging.level.com.example.demo.error.DataExceptionHandler=DEBUG
# GlobalExceptionHandler — ERROR for unexpected exceptions (stack trace included),
# everything else inherited from Spring's ResponseEntityExceptionHandler (WARN)
logging.level.com.example.demo.error.GlobalExceptionHandler=DEBUG
# Suppress the stack-trace log that Spring MVC itself emits for resolved exceptions.
# Useful in production to avoid double-logging when GlobalExceptionHandler already logs.
logging.level.org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler=ERROR
```

#### Stack traces at TRACE level

`JsonExceptionHandler` and `ValidationExceptionHandler` deliberately split their log
output into two statements:

- **DEBUG** — a one-line summary (field path, violation count, etc.) that is safe to
  enable in production without flooding logs.
- **TRACE** — the full exception stack trace, logged separately via `log.trace("Stack
  trace:", ex)`.

This means enabling DEBUG gives you actionable context for every bad request without
stack-trace noise. Enable TRACE only when you need to inspect the Jackson or Validator
internals (e.g. to diagnose a misconfigured deserializer):

```properties
# Stack traces for JSON deserialization failures
logging.level.com.example.demo.error.JsonExceptionHandler=TRACE
# Stack traces for Bean Validation failures
logging.level.com.example.demo.error.ValidationExceptionHandler=TRACE
```

`GlobalExceptionHandler.handleUnexpected` is the exception to this pattern: it logs at
ERROR with the exception object directly (`log.error("Unexpected error", ex)`), so the
stack trace is always captured for genuinely unexpected failures regardless of the
configured level.

### File upload size (for `PAYLOAD_TOO_LARGE`)

```properties
# Maximum size of a single uploaded file (default: 1MB)
spring.servlet.multipart.max-file-size=10MB
# Maximum size of the entire multipart request (default: 10MB)
spring.servlet.multipart.max-request-size=50MB
```

When either limit is exceeded Spring throws `MaxUploadSizeExceededException`, which
`GlobalExceptionHandler` maps to 413 `PAYLOAD_TOO_LARGE`.

## Internationalisation (i18n)

Every `title` and `detail` field in the error body is resolved through Spring's `MessageSource`,
with the locale read from `LocaleContextHolder` at exception-handling time. Translating error
messages to a new language requires only a properties file — no code changes.

### Bundled locales

| File                        | Locale              |
|-----------------------------|---------------------|
| `messages.properties`       | English (fallback)  |
| `messages_pt_BR.properties` | Portuguese — Brazil |

### Adding a locale

Create `messages_<language>[_<COUNTRY>].properties` alongside the existing files and translate
every key. Spring resolves the closest-matching bundle for the request locale automatically.

```properties
# src/main/resources/messages_es.properties
error.validation-failed.title=Validación fallida
error.validation-failed.detail=Uno o más campos no pasaron la validación
error.resource-not-found.title=Recurso no encontrado
error.resource-not-found.detail=El recurso solicitado no fue encontrado.
# … remaining keys …
```

If you package this library as a JAR and your application defines its own `messages.properties`,
list both basenames so Spring merges them:

```properties
spring.messages.basename=messages,classpath:com/example/demo/error/messages
spring.messages.encoding=UTF-8
```

### Locale resolution

By default, Spring MVC's `AcceptHeaderLocaleResolver` maps the `Accept-Language` request header
to a `java.util.Locale`. When the header is absent or no bundle matches, Spring falls back to the
JVM default locale and then to the root `messages.properties`.

To resolve locale from a cookie or session instead, declare a `LocaleResolver` bean:

```java

@Bean
LocaleResolver localeResolver() {
    var resolver = new CookieLocaleResolver("lang");
    resolver.setDefaultLocale(Locale.ENGLISH);
    return resolver;
}
```

### Validation constraint messages

The `violations[].message` field inside `VALIDATION_FAILED` responses comes from **Bean
Validation**, not from the `MessageSource` above. To localise constraint messages, add a
`ValidationMessages_<locale>.properties` file to your classpath:

```properties
# src/main/resources/ValidationMessages_pt_BR.properties
jakarta.validation.constraints.NotBlank.message=não deve estar em branco
jakarta.validation.constraints.Size.message=o tamanho deve estar entre {min} e {max}
```

Bean Validation resolves its bundle against the JVM default locale. To drive it from the
per-request locale, supply a locale-aware `MessageInterpolator` in your validator configuration.

## Full dependency block for tracing support

```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-micrometer-tracing-brave</artifactId>
  </dependency>
  <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-tracing-bridge-brave</artifactId>
  </dependency>
```

> Without these dependencies the `traceId` body field and `X-Trace-Id` header are simply
> absent. All other error-handling behaviour is unaffected.

## Error code reference

| `code`                            | Status | Meaning                                   |
|-----------------------------------|--------|-------------------------------------------|
| `MALFORMED_JSON`                  | 400    | Syntactically invalid JSON                |
| `MALFORMED_REQUEST_BODY`          | 400    | Body unreadable (unclassified)            |
| `UNKNOWN_JSON_FIELD`              | 400    | Field not declared on the target type     |
| `INVALID_ENUM_VALUE`              | 400    | Value not in enum constants               |
| `INVALID_FIELD_VALUE`             | 400    | Value cannot be coerced to target type    |
| `TYPE_MISMATCH`                   | 400    | Wrong JSON token type for target          |
| `INTEGER_OVERFLOW`                | 400    | Numeric value outside type range          |
| `VALIDATION_FAILED`               | 422    | Bean Validation constraint failure        |
| `METHOD_VALIDATION_ERROR`         | 422    | Method-level validation failure           |
| `DUPLICATE_VALUE`                 | 409    | Unique constraint violated                |
| `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | Foreign key constraint violated           |
| `DATA_INTEGRITY_VIOLATION`        | 409    | Other integrity constraint violated       |
| `RESOURCE_NOT_FOUND`              | 404    | Entity or query result not found          |
| `METHOD_NOT_ALLOWED`              | 405    | HTTP method not supported                 |
| `UNSUPPORTED_MEDIA_TYPE`          | 415    | `Content-Type` not accepted               |
| `NOT_ACCEPTABLE`                  | 406    | Requested `Accept` type unavailable       |
| `MISSING_PATH_VARIABLE`           | 400    | Required path variable absent             |
| `MISSING_REQUEST_PARAMETER`       | 400    | Required query parameter absent           |
| `MISSING_REQUEST_PART`            | 400    | Required multipart part absent            |
| `REQUEST_BINDING_ERROR`           | 400    | Servlet request binding failure           |
| `ROUTE_NOT_FOUND`                 | 404    | No handler mapped for the request path    |
| `REQUEST_TIMEOUT`                 | 503    | Async request timed out                   |
| `PAYLOAD_TOO_LARGE`               | 413    | Upload exceeds configured limit           |
| `CONVERSION_NOT_SUPPORTED`        | 500    | No converter for property type            |
| `PARAMETER_TYPE_MISMATCH`         | 400    | Query/path parameter type coercion failed |
| `MESSAGE_NOT_WRITABLE`            | 500    | Response body could not be serialised     |
| `INTERNAL_SERVER_ERROR`           | 500    | Unexpected exception                      |
