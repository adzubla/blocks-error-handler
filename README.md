# Reusable Spring Boot 4 Error Handler

Reusable Spring Boot 4 auto-configuration that turns exceptions into structured
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) responses and
propagates the Micrometer trace ID to every response.

## 1 Why use this library

Out of the box, Spring Boot answers most failures with a generic body — `BasicErrorController`'s
`timestamp` / `status` / `error` / `path` object, or Spring's default Problem Details — that says
*what status* occurred but not *what went wrong*: there is no stable error code, no field
location, and an unhandled exception of any kind becomes a bare 500.

This library replaces that with a single, consistent error contract:

- **Structured, machine-readable bodies.** Every error is an RFC 9457 Problem Details body with a
  stable `code` field (e.g. `INVALID_ENUM_VALUE`, `VALIDATION_FAILED`, `DUPLICATE_VALUE`). Clients
  can branch on `code` without parsing human-readable messages.
- **Precise field-level context.** JSON deserialization errors include the JSONPath of the field
  (`$.address.street`), the rejected value, the expected type, and — for enums — the list of valid
  values. Validation errors list every constraint violation in one response, so clients don't need
  to resubmit to discover all problems.
- **No internal leakage.** Raw database messages, constraint and column names, exception class
  names and stack traces never reach the client — they are logged server-side.
- **Consistent tracing.** The Micrometer trace ID is added to error bodies (`traceId`) and to HTTP
  responses (`X-Trace-Id` header), so a client-side error report can be correlated directly to a
  distributed trace.
- **Drop-in auto-configuration.** Declare the dependency — no `@Import`, no `@ComponentScan`, no
  boilerplate.

## 2 Getting started

### 2.1 Requirements

| Dependency                                                                                                  | Notes                                                                                                      |
|-------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| Spring Boot 4.x                                                                                             | Tested on 4.0.9                                                                                            |
| `spring-boot-starter-webmvc`                                                                                | Required — servlet web applications only                                                                   |
| `spring-boot-starter-validation`                                                                            | Optional — a Bean Validation provider, needed for any `VALIDATION_FAILED` response; also enables `ConstraintViolationExceptionHandler` |
| `spring-tx` (via `spring-boot-starter-jdbc`, `spring-boot-starter-data-jpa`, any Spring Data starter, …)    | Optional — enables `DataExceptionHandler`                                                                  |
| `spring-boot-starter-data-jpa`                                                                              | Optional — enables `JpaExceptionHandler` (and, via `spring-data-commons`, `SpringDataExceptionHandler`) |
| `spring-boot-starter-actuator` + `spring-boot-micrometer-tracing-brave` + `micrometer-tracing-bridge-brave` | Optional — enables `traceId` in bodies and the `X-Trace-Id` header                                         |

The optional dependencies are declared `<optional>true</optional>`, so they are **not** pulled into
your application transitively — add the ones you need. A handler whose dependency is absent simply
isn't registered. `JsonExceptionHandler`, `ValidationExceptionHandler` and `GlobalExceptionHandler`
are always registered (though Spring MVC only validates when a Bean Validation provider is
present).

### 2.2 Installation

```xml
<dependency>
    <groupId>io.adzubla.blocks</groupId>
    <artifactId>blocks-error-handler</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For trace IDs, also add:

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

The JAR ships two auto-configurations, `ErrorHandlerAutoConfiguration` (the exception handlers) and
`ErrorTraceAutoConfiguration` (`ProblemDetailTraceAdvice` and `TraceIdResponseHeaderFilter`).

### 2.3 Disabling or replacing a handler

Every bean can be switched off with a property, and the whole library with
`blocks.error.enabled=false`:

| Property                                    | Bean                                  |
|---------------------------------------------|---------------------------------------|
| `blocks.error.enabled`                      | everything below                      |
| `blocks.error.json.enabled`                 | `JsonExceptionHandler`                |
| `blocks.error.validation.enabled`           | `ValidationExceptionHandler`          |
| `blocks.error.constraint-violation.enabled` | `ConstraintViolationExceptionHandler` |
| `blocks.error.data.enabled`                 | `DataExceptionHandler`                |
| `blocks.error.jpa.enabled`                  | `JpaExceptionHandler`                 |
| `blocks.error.spring-data.enabled`          | `SpringDataExceptionHandler`          |
| `blocks.error.global.enabled`               | `GlobalExceptionHandler`              |
| `blocks.error.trace.body.enabled`           | `ProblemDetailTraceAdvice`            |
| `blocks.error.trace.header.enabled`         | `TraceIdResponseHeaderFilter`         |

All default to `true`. Exceptions a disabled handler would have covered fall through to
`GlobalExceptionHandler` (or Spring's defaults, if that is disabled too), which answers them with
its generic mapping:

- JSON errors → 400 `MALFORMED_REQUEST_BODY`;
- validation errors (`MethodArgumentNotValidException`, `HandlerMethodValidationException`) → 422
  `VALIDATION_FAILED`, with Spring's body (no `violations` array);
- data access, JPA and `ConstraintViolationException` errors → 500 `INTERNAL_SERVER_ERROR`.

To customise a handler instead, declare your own bean of the same type — typically a subclass
overriding one method — and the library's bean backs off:

```java
@RestControllerAdvice
class MyDataExceptionHandler extends DataExceptionHandler {

    MyDataExceptionHandler(MessageSource messageSource, DataExceptionProperties properties) {
        super(messageSource, properties);
    }

    @Override
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        // your response
    }
}
```

`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so with
`spring.mvc.problemdetails.enabled=true` Spring Boot's own `ResponseEntityExceptionHandler` backs
off in its favour rather than competing with it.

## 3 Handlers

### 3.1 `JsonExceptionHandler` — 400 Bad Request

Handles `HttpMessageNotReadableException` and maps its Jackson 3 cause to a structured response:

| Cause                             | `code`                   | Extra fields                                          |
|-----------------------------------|--------------------------|-------------------------------------------------------|
| `PropertyBindingException` (unrecognized or ignored field) | `UNKNOWN_JSON_FIELD` | `path`, `validValues` (the known fields)              |
| `InvalidFormatException` (enum)   | `INVALID_ENUM_VALUE`     | `path`, `invalidValue`, `expectedType`, `validValues` |
| `InvalidFormatException` (scalar) | `INVALID_FIELD_VALUE`    | `path`, `invalidValue`, `expectedType`                |
| `InvalidNullException`            | `NULL_VALUE_NOT_ALLOWED` | `path`                                                |
| `InvalidTypeIdException`          | `INVALID_TYPE_ID`        | `path`, `invalidValue`, `expectedType`                |
| `MismatchedInputException`        | `TYPE_MISMATCH`          | `path`, `expectedType`                                |
| `ValueInstantiationException`     | `INVALID_FIELD_VALUE`    | `path`, `expectedType`                                |
| `MissingInjectableValueException` | `INTERNAL_SERVER_ERROR` (500) | — (server-side mapping defect)                        |
| `InputCoercionException`          | `INTEGER_OVERFLOW`       | `expectedType`, `validRange`, `line`, `column`        |
| `StreamReadException`             | `MALFORMED_JSON`         | `line`, `column`                                      |
| Other (e.g. missing body)         | `MALFORMED_REQUEST_BODY` | —                                                     |

`UNKNOWN_JSON_FIELD` requires Jackson to reject unknown fields, which Spring Boot doesn't do by
default (see [Configuration](#41-jackson--unknown-fields)). For enums, `validValues` lists each
constant the way your `JsonMapper` writes it, so `@JsonProperty` / `@JsonValue` names are listed
rather than the Java constant names.

```json
{
  "title": "Invalid enum value",
  "status": 400,
  "detail": "Cannot deserialize value 'UNKNOWN' as Category at path $.category — valid values: [ELECTRONICS, BOOKS, CLOTHING]",
  "instance": "/products",
  "code": "INVALID_ENUM_VALUE",
  "path": "$.category",
  "invalidValue": "UNKNOWN",
  "expectedType": "Category",
  "validValues": ["ELECTRONICS", "BOOKS", "CLOTHING"],
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

### 3.2 `ValidationExceptionHandler` and `ConstraintViolationExceptionHandler` — 422 Unprocessable Content

Both answer Bean Validation failures with `code=VALIDATION_FAILED` and the same response shape:

- `ValidationExceptionHandler` handles Spring MVC's own validation: `MethodArgumentNotValidException`
  (`@Valid` on a `@RequestBody`), `HandlerMethodValidationException` (constraints on individual
  controller parameters) and `MethodValidationException` (`@Validated` beans when
  `spring.validation.method.adapt-constraint-violations=true`).
- `ConstraintViolationExceptionHandler` handles `ConstraintViolationException`, raised by
  `@Validated` on non-controller beans (e.g. a service layer) or by direct `Validator` calls.

```json
{
  "title": "Validation failed",
  "status": 422,
  "detail": "One or more fields failed validation",
  "instance": "/products",
  "code": "VALIDATION_FAILED",
  "violations": [
    { "path": "$.name", "invalidValue": "", "message": "must not be blank" },
    { "path": "$.price", "invalidValue": "-1", "message": "must be greater than 0" }
  ],
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

Each violation has:

- **`path`** — a JSONPath-style location in the client's request input (the body, a path variable
  or a query parameter), so every `VALIDATION_FAILED` response uses one notation no matter which
  layer validated it. A constraint on the request object as a whole (a class-level constraint) is
  reported as `$`. A constrained element of a collection parameter carries its index, e.g.
  `List<@NotBlank String> tags` reports `$.tags[1]`. For `ConstraintViolationException` the path drops Java-only names — the method
  name, and a parameter name when the violation is on a property of that parameter — so
  `create(@Valid Request request)` reports `$.name`, `find(@NotBlank String code)` reports `$.code`
  and `tag(List<@NotBlank String> tags)` reports `$.tags[0]`. This is best-effort: a service method
  can't know how its parameters map back to the request, so it assumes the validated object is the
  request body.
- **`invalidValue`** — the rejected value as a string; JSON `null` when the rejected value was
  `null`; absent when there is no single rejected value (a class-level or cross-parameter
  constraint).
- **`message`** — the constraint message (see [Validation constraint messages](#53-validation-constraint-messages)).

A violation on a method's *return value* — controller or service — means the server produced
invalid data, not that the client sent it, so it is answered as 500 `INTERNAL_SERVER_ERROR` and
logged at `ERROR`.

### 3.3 `DataExceptionHandler`, `JpaExceptionHandler` and `SpringDataExceptionHandler` — 400 / 404 / 409

`DataExceptionHandler` handles Spring's data access exceptions (`org.springframework.dao.*`, from
`spring-tx`); `JpaExceptionHandler` handles JPA's own exceptions, which reach the
application untranslated when thrown outside a repository proxy; `SpringDataExceptionHandler` handles
Spring Data's `PropertyReferenceException`.

| Exception                           | Condition              | `code`                            | Status |
|-------------------------------------|------------------------|-----------------------------------|--------|
| `DuplicateKeyException`             | any                    | `DUPLICATE_VALUE`                 | 409    |
| `DataIntegrityViolationException`   | unique constraint      | `DUPLICATE_VALUE`                 | 409    |
| `DataIntegrityViolationException`   | foreign key constraint | `REFERENTIAL_INTEGRITY_VIOLATION` | 409    |
| `DataIntegrityViolationException`   | other                  | `DATA_INTEGRITY_VIOLATION`        | 409    |
| `OptimisticLockingFailureException` | any                    | `OPTIMISTIC_LOCKING_FAILURE`      | 409    |
| `EmptyResultDataAccessException`    | any                    | `RESOURCE_NOT_FOUND`              | 404    |
| `EntityNotFoundException` (JPA)     | any                    | `RESOURCE_NOT_FOUND`              | 404    |
| `NoResultException` (JPA)           | any                    | `RESOURCE_NOT_FOUND`              | 404    |
| `OptimisticLockException` (JPA)     | any                    | `OPTIMISTIC_LOCKING_FAILURE`      | 409    |
| `EntityExistsException` (JPA)       | any                    | `DATA_INTEGRITY_VIOLATION`        | 409    |
| `PropertyReferenceException` (Spring Data) | any             | `INVALID_PROPERTY_REFERENCE`      | 400    |

`PropertyReferenceException` is what an unknown `?sort=` property produces. Repository proxies
usually wrap it in an `InvalidDataAccessApiUsageException`; the handler still receives it, because
Spring looks through the cause chain. The offending property name is returned as `invalidValue`.

```json
{
  "title": "Duplicate value",
  "status": 409,
  "detail": "A resource with the same value already exists.",
  "instance": "/products",
  "code": "DUPLICATE_VALUE",
  "invalidValue": "Laptop",
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

The handler is designed for PostgreSQL:

- **Classification.** Unique and foreign-key violations are recognised by the SQLState of the
  underlying `SQLException` (`23505` / `23503`), so they are classified correctly whatever language
  PostgreSQL writes its messages in (`lc_messages`). The English message text is a fallback for
  drivers that report no SQLState.
- **Both foreign-key directions.** `REFERENTIAL_INTEGRITY_VIOLATION` has a different `detail` for
  inserting/updating a row that references a missing one (*"The request references a resource that
  does not exist."*) and for updating/deleting a row that other rows still reference (*"The resource
  is still referenced by other resources and cannot be removed or changed."*).
- **`invalidValue`.** For `DUPLICATE_VALUE` and `REFERENTIAL_INTEGRITY_VIOLATION`, the offending
  value is extracted from PostgreSQL's `Detail: Key (column)=(value) ...` line (in English or
  translated) and exposed as `invalidValue`; it is omitted when it can't be extracted. It is the
  value *as stored by the database*, which is not necessarily what the client sent — e.g. for a
  unique index on a derived or server-generated column. If that is a concern, turn it off (see
  [Configuration](#43-data-access--invalidvalue)). The column name is deliberately not exposed: it
  may not match a client-facing field name at all (different casing, a related entity, a generated
  key).

#### 3.3.1 Unhandled data access exceptions

Every other `org.springframework.dao.DataAccessException` subtype, and any raw JPA exception
Spring's translation never reaches, falls through to `GlobalExceptionHandler`'s catch-all:
**HTTP 500**, `code=INTERNAL_SERVER_ERROR`, a generic `detail`, and the full exception (including
any raw SQL or driver detail) logged server-side at `ERROR`. Examples:

| Category                         | Examples                                                                                                                                                                                                                             |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Transient/lock failures          | `CannotAcquireLockException`, `PessimisticLockingFailureException`, `DeadlockLoseDataAccessException`, `CannotSerializeTransactionException`, `QueryTimeoutException`                                                                |
| Connectivity/config failures     | `DataAccessResourceFailureException`, `CannotGetJdbcConnectionException`                                                                                                                                                             |
| Programming/schema errors        | `InvalidDataAccessResourceUsageException` (incl. `BadSqlGrammarException`), `TypeMismatchDataAccessException`                                                                                                                        |
| Uncategorized                    | `PermissionDeniedDataAccessException`, `UncategorizedDataAccessException` / `UncategorizedSQLException`                                                                                                                              |
| Raw JPA (not repository-proxied) | `PersistenceException`, `PessimisticLockException`, `LockTimeoutException`, `NonUniqueResultException`, `TransactionRequiredException`, `RollbackException` |

That last row matters in practice: Spring only translates JPA exceptions into the
`org.springframework.dao` hierarchy when they're thrown from behind a `@Repository` bean under
`PersistenceExceptionTranslationPostProcessor`. A JPA exception thrown directly from an
`EntityManager` outside a repository proxy (e.g. in a `@Transactional` service method) keeps its
native `jakarta.persistence` type. `EntityNotFoundException`, `NoResultException`,
`OptimisticLockException` and `EntityExistsException` are answered like their Spring-translated
equivalents (see the table above); the others go to the 500 catch-all.

### 3.4 `GlobalExceptionHandler` — Spring MVC infrastructure + catch-all

Extends `ResponseEntityExceptionHandler` to handle the standard Spring MVC exceptions (405, 415,
400, 404, 413, 503, …), adding a stable `code` to each (see the
[error code reference](#6-error-code-reference)). Their `title` and `detail` are Spring's own.

```json
{
  "title": "Method Not Allowed",
  "status": 405,
  "detail": "Method 'DELETE' is not supported.",
  "instance": "/products",
  "code": "METHOD_NOT_ALLOWED",
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

Anything not claimed by a more specific `@ExceptionHandler` falls to a final
`@ExceptionHandler(Exception.class)`, which returns 500 `INTERNAL_SERVER_ERROR` and logs the stack
trace at `ERROR`:

```json
{
  "title": "Internal server error",
  "status": 500,
  "detail": "An unexpected error occurred.",
  "instance": "/products",
  "code": "INTERNAL_SERVER_ERROR",
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

Two kinds of exception are exempt from the catch-all:

- **`@ResponseStatus`-annotated exceptions** (directly or via a superclass) keep their declared
  status, with `code=ERROR_RESPONSE` and the annotation's `reason` (resolved as a message code if
  one matches) as `detail`.
- **Spring Security's `AccessDeniedException` / `AuthenticationException`** (e.g. thrown by
  `@PreAuthorize`) are rethrown, so Spring Security's `ExceptionTranslationFilter` produces its
  usual 401/403 response. Spring Security stays an optional dependency.

### 3.5 Trace ID — `ProblemDetailTraceAdvice` and `TraceIdResponseHeaderFilter`

Both act only while a trace span is active, which is the case for every request once Micrometer
Tracing is on the classpath: Brave assigns a trace ID even to a request it does not sample, so
the sampling probability does not affect whether the ID appears in responses.

- `ProblemDetailTraceAdvice` adds `"traceId": "<hex>"` to every `ProblemDetail` body produced by an
  `@ExceptionHandler`. Normal responses are not touched.
- `TraceIdResponseHeaderFilter` sets an `X-Trace-Id: <hex>` header on every response, success and
  error alike — or only on error responses (status 400+) with
  `blocks.error.trace.header.error-only=true`. It runs at `Ordered.HIGHEST_PRECEDENCE + 2`, right
  after the `ServerHttpObservationFilter` that starts the span, so the header also reaches
  responses produced by filters that answer the request themselves (a Spring Security 401/403, a
  CORS rejection).

Errors raised outside Spring MVC (e.g. in a servlet filter) are rendered by Spring Boot's error
controller, so they get neither a `code` nor a `traceId` body field.

## 4 Configuration

### 4.1 Jackson — unknown fields

```properties
# Required for UNKNOWN_JSON_FIELD responses. Without it, Jackson silently ignores unrecognised
# fields and JsonExceptionHandler never sees an UnrecognizedPropertyException.
spring.jackson.deserialization.fail-on-unknown-properties=true
```

Properties marked `@JsonIgnore` / `@JsonIgnoreProperties` are a separate case: by default Jackson
accepts them in the payload and silently drops them. To reject them, enable:

```properties
spring.jackson.deserialization.fail-on-ignored-properties=true
```

Jackson then throws `IgnoredPropertyException`, which `JsonExceptionHandler` reports as
`UNKNOWN_JSON_FIELD` too (same `path` and `validValues` fields), so clients see one code for
"this field is not accepted". With only `fail-on-unknown-properties` enabled, ignored properties
are still dropped without error.

### 4.2 Tracing

```properties
# Fraction of requests that are sampled, i.e. exported to your tracing backend (0.0–1.0).
# Defaults to 0.1. It does not change whether responses carry an X-Trace-Id header and a traceId
# field, but an unsampled request's ID will not be found in the backend: set it to 1.0 in
# development to look every request up.
management.tracing.sampling.probability=1.0

# Only add the X-Trace-Id response header to error responses (status 400+). Defaults to false.
blocks.error.trace.header.error-only=true
```

To stop exposing trace IDs without removing the tracing dependencies, use
`blocks.error.trace.body.enabled=false` and `blocks.error.trace.header.enabled=false`.

### 4.3 Data access — `invalidValue`

```properties
# Omit invalidValue from DUPLICATE_VALUE / REFERENTIAL_INTEGRITY_VIOLATION responses.
# Defaults to true (included).
blocks.error.data.expose-invalid-value=false
```

### 4.4 File upload size (for `PAYLOAD_TOO_LARGE`)

```properties
# Maximum size of a single uploaded file (default: 1MB)
spring.servlet.multipart.max-file-size=10MB
# Maximum size of the entire multipart request (default: 10MB)
spring.servlet.multipart.max-request-size=50MB
```

When either limit is exceeded Spring throws `MaxUploadSizeExceededException`, which
`GlobalExceptionHandler` maps to 413 `PAYLOAD_TOO_LARGE`. Any other `MultipartException` — a
multipart body that can't be parsed, or a non-multipart request sent to an endpoint expecting one —
is answered as 400 `MALFORMED_MULTIPART_REQUEST`.

### 4.5 Logging

Each handler logs under its own class name, at a level that reflects who is at fault:

| Logger                                | Level                                                                                                     |
|---------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `JsonExceptionHandler`                | `DEBUG`                                                                                                   |
| `ValidationExceptionHandler`          | `DEBUG`; `ERROR` with stack trace for return-value violations                                             |
| `ConstraintViolationExceptionHandler` | `DEBUG`; `ERROR` with stack trace for return-value violations                                             |
| `DataExceptionHandler`                | `WARN` for integrity violations and optimistic locking failures (including the raw database message); `DEBUG` for empty results |
| `JpaExceptionHandler`                 | `DEBUG` for not-found; `WARN` for optimistic locking and entity-exists conflicts                          |
| `SpringDataExceptionHandler`          | `DEBUG`                                                                                                   |
| `GlobalExceptionHandler`              | `WARN` for 4xx; `ERROR` with stack trace for 5xx and unexpected exceptions                                |

Client errors are logged as a one-line summary (status, code, field path or violation count); the
stack trace is logged separately at `TRACE`. Enable `TRACE` only when you need to inspect Jackson or
Validator internals:

```properties
logging.level.io.adzubla.blocks.error.handler.JsonExceptionHandler=TRACE
logging.level.io.adzubla.blocks.error.handler.ValidationExceptionHandler=TRACE
```

Spring MVC also logs some exceptions itself — e.g. a `WARN` for every 405, under the
`org.springframework.web.servlet.PageNotFound` category. To avoid logging them twice:

```properties
logging.level.org.springframework.web.servlet.PageNotFound=ERROR
```

## 5 Internationalisation (i18n)

The `title` and `detail` the library writes are resolved with the locale from
`LocaleContextHolder` at exception-handling time. Each key is looked up first in the application's
own `MessageSource`, then in the library's bundle, so translating or rewording a message requires
only a properties file. (Responses for Spring MVC infrastructure exceptions use Spring's own text,
which Spring looks up in your `MessageSource` under its `problemDetail.title.<exception class>` and
`problemDetail.<exception class>` keys.)

The library's bundle lives under the namespaced basename `io/adzubla/blocks/error/messages`, so it
never clashes with the application's `messages.properties` and is found regardless of
`spring.messages.basename`.

| File (under `io/adzubla/blocks/error/`) | Locale              |
|-----------------------------------------|---------------------|
| `messages.properties`                   | English (fallback)  |
| `messages_pt_BR.properties`             | Portuguese — Brazil |

### 5.1 Overriding messages and adding a locale

Define any key in your application's own message bundle (by default `messages*.properties`) and it
takes precedence over the library's text; keys you don't define keep the library's text. To add a
language, translate the keys into the matching locale file of your bundle:

```properties
# your application's src/main/resources/messages_es.properties
error.validation-failed.title=Validación fallida
error.validation-failed.detail=Uno o más campos no pasaron la validación
error.resource-not-found.title=Recurso no encontrado
error.resource-not-found.detail=El recurso solicitado no fue encontrado.
# … remaining keys …
```

### 5.2 Locale resolution

By default, Spring MVC's `AcceptHeaderLocaleResolver` maps the `Accept-Language` request header to
a `java.util.Locale`. When the header names a locale with no matching bundle (e.g. `fr`), the
library falls back to the English bundle — never to the server's JVM locale, so a server running in
another locale doesn't answer English-speaking clients in its own language. When the header is
absent, the resolver itself uses the JVM default locale; to pin that too, set
`spring.web.locale=en` (or declare a `LocaleResolver`).

To resolve the locale from a cookie or session instead, declare a `LocaleResolver` bean:

```java
@Bean
LocaleResolver localeResolver() {
    var resolver = new CookieLocaleResolver("lang");
    resolver.setDefaultLocale(Locale.ENGLISH);
    return resolver;
}
```

### 5.3 Validation constraint messages

The `violations[].message` field comes from **Bean Validation**, not from the `MessageSource`
above. Spring's validator interpolates it with the request locale, and Hibernate Validator ships
translations for many languages (Portuguese included). To override or add messages, put a
`ValidationMessages_<locale>.properties` file on your classpath:

```properties
# src/main/resources/ValidationMessages_pt_BR.properties
jakarta.validation.constraints.NotBlank.message=não pode estar em branco
jakarta.validation.constraints.Size.message=o tamanho deve estar entre {min} e {max}
```

## 6 Error code reference

| `code`                            | Status | Meaning                                                                       |
|-----------------------------------|--------|-------------------------------------------------------------------------------|
| `MALFORMED_JSON`                  | 400    | Syntactically invalid JSON                                                    |
| `MALFORMED_REQUEST_BODY`          | 400    | Body unreadable (unclassified, or missing)                                    |
| `UNKNOWN_JSON_FIELD`              | 400    | Field not declared on the target type                                         |
| `INVALID_ENUM_VALUE`              | 400    | Value not in enum constants                                                   |
| `INVALID_FIELD_VALUE`             | 400    | Value cannot be coerced to target type                                        |
| `TYPE_MISMATCH`                   | 400    | Wrong JSON token type for target                                              |
| `NULL_VALUE_NOT_ALLOWED`          | 400    | `null` sent for a field that forbids it                                       |
| `INVALID_TYPE_ID`                 | 400    | Unknown polymorphic type id                                                   |
| `INVALID_PROPERTY_REFERENCE`      | 400    | Unknown property in a Spring Data `Sort` (e.g. `?sort=nope`)                  |
| `INTEGER_OVERFLOW`                | 400    | Numeric value outside type range                                              |
| `VALIDATION_FAILED`               | 422    | Bean Validation constraint failure                                            |
| `METHOD_VALIDATION_ERROR`         | 500    | `MethodValidationException` reaching `GlobalExceptionHandler`, i.e. only when `blocks.error.validation.enabled=false` (otherwise it is a 422 `VALIDATION_FAILED`, or a 500 `INTERNAL_SERVER_ERROR` for a return value) |
| `DUPLICATE_VALUE`                 | 409    | Unique constraint violated                                                    |
| `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | Foreign key constraint violated                                               |
| `DATA_INTEGRITY_VIOLATION`        | 409    | Other integrity constraint violated                                           |
| `OPTIMISTIC_LOCKING_FAILURE`      | 409    | Resource modified by another request                                          |
| `RESOURCE_NOT_FOUND`              | 404    | Entity or query result not found                                              |
| `METHOD_NOT_ALLOWED`              | 405    | HTTP method not supported                                                     |
| `UNSUPPORTED_MEDIA_TYPE`          | 415    | `Content-Type` not accepted                                                   |
| `NOT_ACCEPTABLE`                  | 406    | Requested `Accept` type unavailable                                           |
| `MISSING_PATH_VARIABLE`           | 500    | Path variable declared on the handler but absent from the mapping             |
| `MISSING_REQUEST_PARAMETER`       | 400    | Required query parameter absent                                               |
| `MISSING_REQUEST_PART`            | 400    | Required multipart part absent                                                |
| `REQUEST_BINDING_ERROR`           | 400    | Other request binding failure (e.g. missing header or cookie)                 |
| `ROUTE_NOT_FOUND`                 | 404    | No handler or static resource for the request path                            |
| `REQUEST_TIMEOUT`                 | 503    | Async request timed out                                                       |
| `PAYLOAD_TOO_LARGE`               | 413    | Upload exceeds configured limit                                               |
| `MALFORMED_MULTIPART_REQUEST`     | 400    | Multipart body unparsable, or request not multipart                           |
| `CONVERSION_NOT_SUPPORTED`        | 500    | No converter for property type                                                |
| `PARAMETER_TYPE_MISMATCH`         | 400    | Query/path parameter type coercion failed                                     |
| `MESSAGE_NOT_WRITABLE`            | 500    | Response body could not be serialised                                         |
| `ERROR_RESPONSE`                  | varies | `@ResponseStatus` exception or `ErrorResponseException` (e.g. `ResponseStatusException`); status comes from the exception |
| `INTERNAL_SERVER_ERROR`           | 500    | Unexpected exception, or a constraint violation on a return value             |
