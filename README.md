# Reusable Spring Boot 4 Error Handler

Reusable Spring Boot 4 auto-configuration that turns exceptions into structured
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) responses with a stable error
code, and adds the Micrometer trace ID to responses.

## 1 Why use this library

Out of the box, Spring Boot answers most failures with a generic body that says *what status*
occurred but not *what went wrong*: there is no stable error code, no field location, and any
unhandled exception becomes a bare 500.

This library replaces that with a single, consistent error contract:

- **Machine-readable bodies.** Errors are Problem Details bodies with a stable `code` field (e.g.
  `INVALID_ENUM_VALUE`, `VALIDATION_FAILED`, `DUPLICATE_VALUE`), so clients can branch on `code`
  without parsing messages.
- **Precise field-level context.** JSON errors include the JSONPath of the field, the rejected
  value, the expected type and, for enums, the valid values. Validation errors list every violation
  in one response, so clients don't need to resubmit to discover all problems.
- **No internal leakage.** Raw database messages, constraint and column names, exception class names
  and stack traces are logged server-side and never reach the client.
- **Traceable errors.** The trace ID is added to error bodies (`traceId`) and to responses
  (`X-Trace-Id` header), so a client's error report can be matched to a distributed trace.
- **Drop-in.** Declare the dependency: no `@Import`, no `@ComponentScan`, no boilerplate.

## 2 Getting started

### 2.1 Requirements

- Java 17 or later
- Spring Boot 4.x (tested on 4.0.9) with Jackson 3, Boot's default
- A servlet application using `spring-boot-starter-webmvc`. WebFlux is not supported.

### 2.2 Installation

```xml
<dependency>
    <groupId>io.adzubla.blocks</groupId>
    <artifactId>blocks-error-handler</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

JSON and Spring MVC errors, and the catch-all for unexpected exceptions, work with no further
setup. The other features depend on what your application already uses. The library doesn't bring
these dependencies in, so add the ones you need:

| Feature                           | Needs                                                                        |
|-----------------------------------|------------------------------------------------------------------------------|
| Validation errors                 | `spring-boot-starter-validation`                                             |
| Data access errors                | `spring-tx`, which comes with `spring-boot-starter-jdbc` or any data starter |
| JPA errors                        | `spring-boot-starter-data-jpa`, or any other source of `jakarta.persistence` |
| Unknown Spring Data sort property | Any Spring Data starter                                                      |
| Trace ID in bodies and headers    | `spring-boot-micrometer-tracing-brave` + `micrometer-tracing-bridge-brave`   |

Tracing is tested with the Brave bridge, and doesn't need `spring-boot-starter-actuator`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
```

## 3 Error responses

### 3.1 Response format

Errors handled by Spring MVC look like this, plus the extra fields described in the sections below.
`traceId` is present when [tracing](#4-trace-id) is on.

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

`code` is the stable field to branch on (see the [error code reference](#9-error-code-reference)).
`title` and `detail` are for people and can be [translated or reworded](#8-internationalisation-i18n).

Two kinds of error don't use this format: the 401/403 responses written by
[Spring Security](#5-spring-security), and errors raised outside Spring MVC, such as in a servlet
filter, which Spring Boot's error controller renders.

### 3.2 JSON request body errors

The Exception column shows the Jackson 3 failure. Spring delivers most of these as the cause of an
`HttpMessageNotReadableException`.

| Situation                                                | Exception                                                       | `code`                   | Status | Extra fields                                          |
|----------------------------------------------------------|-----------------------------------------------------------------|--------------------------|--------|-------------------------------------------------------|
| Syntactically invalid JSON                               | `StreamReadException`                                           | `MALFORMED_JSON`         | 400    | `line`, `column`                                      |
| Unknown field (see below)                                | `PropertyBindingException`                                      | `UNKNOWN_JSON_FIELD`     | 400    | `path`, `validValues` (the known fields)              |
| Value not in an enum                                     | `InvalidFormatException`                                        | `INVALID_ENUM_VALUE`     | 400    | `path`, `invalidValue`, `expectedType`, `validValues` |
| Value can't be converted to the field's type             | `InvalidFormatException`                                        | `INVALID_FIELD_VALUE`    | 400    | `path`, `invalidValue`, `expectedType`                |
| The target type's constructor rejects the value          | `ValueInstantiationException`                                   | `INVALID_FIELD_VALUE`    | 400    | `path`, `expectedType`                                |
| Wrong JSON token, e.g. an object for a string            | `MismatchedInputException`                                      | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| `null` for a field that forbids it                       | `InvalidNullException`                                          | `NULL_VALUE_NOT_ALLOWED` | 400    | `path`                                                |
| Unknown polymorphic type id                              | `InvalidTypeIdException`                                        | `INVALID_TYPE_ID`        | 400    | `path`, `invalidValue`, `expectedType`                |
| Number outside the range of its integer type             | `InputCoercionException`                                        | `INTEGER_OVERFLOW`       | 400    | `expectedType`, `validRange`, `line`, `column`        |
| Anything else, e.g. a missing body                       | `HttpMessageNotReadableException` with any other cause, or none | `MALFORMED_REQUEST_BODY` | 400    | none                                                  |
| Server-side defect: abstract type with no known subtypes | `InvalidDefinitionException`                                    | `INTERNAL_SERVER_ERROR`  | 500    | none                                                  |
| Server-side defect: missing injectable value             | `MissingInjectableValueException`                               | `INTERNAL_SERVER_ERROR`  | 500    | none                                                  |

The 500 responses are logged at `ERROR`.

For enums, `validValues` lists each constant the way your `JsonMapper` writes it, so `@JsonProperty`
and `@JsonValue` names are listed rather than the Java constant names.

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

**Rejecting unknown fields.** Spring Boot ignores unknown fields by default, so you only get
`UNKNOWN_JSON_FIELD` if you enable the first property below. The second one also rejects properties
marked `@JsonIgnore` / `@JsonIgnoreProperties`, which are otherwise dropped without error, and
reports them with the same code:

```properties
spring.jackson.deserialization.fail-on-unknown-properties=true
spring.jackson.deserialization.fail-on-ignored-properties=true
```

### 3.3 Validation errors

| Situation                                                                                      | Exception                          | `code`                  | Status | Extra fields |
|------------------------------------------------------------------------------------------------|------------------------------------|-------------------------|--------|--------------|
| `@Valid` on a request body                                                                     | `MethodArgumentNotValidException`  | `VALIDATION_FAILED`     | 422    | `violations` |
| Constraints on controller parameters                                                           | `HandlerMethodValidationException` | `VALIDATION_FAILED`     | 422    | `violations` |
| `@Validated` on another bean, such as a service, or a direct `Validator` call                  | `ConstraintViolationException`     | `VALIDATION_FAILED`     | 422    | `violations` |
| `@Validated` on another bean, with `spring.validation.method.adapt-constraint-violations=true` | `MethodValidationException`        | `VALIDATION_FAILED`     | 422    | `violations` |
| Constraint on a method's return value                                                          | Any of the last three above        | `INTERNAL_SERVER_ERROR` | 500    | none         |

A return-value violation means the server produced invalid data, so it is logged at `ERROR`.

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

- **`path`**: the JSONPath-style location in the request (see the table below).
- **`invalidValue`**: the rejected value as a string, or JSON `null` if the value was `null`. It is
  absent when there is no single rejected value (a class-level or cross-parameter constraint).
- **`message`**: the constraint message (see
  [Validation constraint messages](#83-validation-constraint-messages)).

| Constraint on                            | Example                                     | `path`             |
|------------------------------------------|---------------------------------------------|--------------------|
| A field of a request body                | `@NotBlank String name`                     | `$.name`           |
| A nested field                           | `@NotBlank String street` in `address`      | `$.address.street` |
| A controller or service parameter        | `find(@NotBlank String code)`               | `$.code`           |
| An element of a collection parameter     | `tag(List<@NotBlank String> tags)`          | `$.tags[1]`        |
| A field of a validated service parameter | `create(@Valid Request request)`            | `$.name`           |
| A whole object, or several parameters    | A class-level or cross-parameter constraint | `$`                |

Paths from a service layer are best-effort: a service can't know how its parameters map to the
request, so it assumes the validated object is the request body.

### 3.4 Data access errors

| Situation                                              | Exception                           | `code`                            | Status | Extra fields   |
|--------------------------------------------------------|-------------------------------------|-----------------------------------|--------|----------------|
| Duplicate key                                          | `DuplicateKeyException`             | `DUPLICATE_VALUE`                 | 409    | `invalidValue` |
| Unique constraint violated                             | `DataIntegrityViolationException`   | `DUPLICATE_VALUE`                 | 409    | `invalidValue` |
| Foreign key constraint violated                        | `DataIntegrityViolationException`   | `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | `invalidValue` |
| Other integrity constraint violated                    | `DataIntegrityViolationException`   | `DATA_INTEGRITY_VIOLATION`        | 409    | none           |
| Concurrent modification                                | `OptimisticLockingFailureException` | `OPTIMISTIC_LOCKING_FAILURE`      | 409    | none           |
| Expected row not found                                 | `EmptyResultDataAccessException`    | `RESOURCE_NOT_FOUND`              | 404    | none           |
| Entity not found (JPA)                                 | `EntityNotFoundException`           | `RESOURCE_NOT_FOUND`              | 404    | none           |
| Query with no result (JPA)                             | `NoResultException`                 | `RESOURCE_NOT_FOUND`              | 404    | none           |
| Concurrent modification (JPA)                          | `OptimisticLockException`           | `OPTIMISTIC_LOCKING_FAILURE`      | 409    | none           |
| Entity already exists (JPA)                            | `EntityExistsException`             | `DATA_INTEGRITY_VIOLATION`        | 409    | none           |
| Unknown sort property, e.g. `?sort=nope` (Spring Data) | `PropertyReferenceException`        | `INVALID_PROPERTY_REFERENCE`      | 400    | `invalidValue` |

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

**`invalidValue`.** `DUPLICATE_VALUE` and `REFERENTIAL_INTEGRITY_VIOLATION` include the offending
value when it can be read from the database error. `INVALID_PROPERTY_REFERENCE` includes the unknown
property name. The database value is the one *as stored*, which may not be what the client sent, for
example with a unique index on a derived or server-generated column. To omit it from the two database
codes:

```properties
blocks.error.data.expose-invalid-value=false
```

**Foreign keys.** `REFERENTIAL_INTEGRITY_VIOLATION` has a different `detail` for a row that references
a missing one and for a row that others still reference.

**Databases.** Classification and `invalidValue` are designed for PostgreSQL, in any message language
(`lc_messages`). On other databases, a violation may be reported as `DATA_INTEGRITY_VIOLATION`, without
`invalidValue`.

**Everything else is a 500.** Any other data access failure gets the generic 500
`INTERNAL_SERVER_ERROR` body, with the full exception logged at `ERROR`. That includes:

- lock and transient failures, such as `CannotAcquireLockException` and `QueryTimeoutException`
- connection failures, such as `DataAccessResourceFailureException`
- SQL and schema errors, such as `BadSqlGrammarException`
- other raw JPA exceptions, such as `NonUniqueResultException` and `LockTimeoutException`

### 3.5 Spring MVC and unexpected errors

| Situation                                                                             | Exception                                                                         | `code`                        | Status | Extra fields |
|---------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|-------------------------------|--------|--------------|
| HTTP method not supported                                                             | `HttpRequestMethodNotSupportedException`                                          | `METHOD_NOT_ALLOWED`          | 405    | none         |
| `Content-Type` not accepted                                                           | `HttpMediaTypeNotSupportedException`                                              | `UNSUPPORTED_MEDIA_TYPE`      | 415    | none         |
| Requested `Accept` type unavailable                                                   | `HttpMediaTypeNotAcceptableException`                                             | `NOT_ACCEPTABLE`              | 406    | none         |
| No handler or static resource for the path                                            | `NoResourceFoundException`, `NoHandlerFoundException`                             | `ROUTE_NOT_FOUND`             | 404    | none         |
| Required query parameter absent                                                       | `MissingServletRequestParameterException`                                         | `MISSING_REQUEST_PARAMETER`   | 400    | none         |
| Required multipart part absent                                                        | `MissingServletRequestPartException`                                              | `MISSING_REQUEST_PART`        | 400    | none         |
| Other binding failure, e.g. missing header or cookie                                  | `ServletRequestBindingException`                                                  | `REQUEST_BINDING_ERROR`       | 400    | none         |
| Query or path parameter of the wrong type                                             | `TypeMismatchException`                                                           | `PARAMETER_TYPE_MISMATCH`     | 400    | none         |
| Upload exceeds the configured limit                                                   | `MaxUploadSizeExceededException`                                                  | `PAYLOAD_TOO_LARGE`           | 413    | none         |
| Multipart body unparsable, or request not multipart                                   | `MultipartException`                                                              | `MALFORMED_MULTIPART_REQUEST` | 400    | none         |
| Async request timed out                                                               | `AsyncRequestTimeoutException`                                                    | `REQUEST_TIMEOUT`             | 503    | none         |
| Path variable declared but absent from the mapping                                    | `MissingPathVariableException`                                                    | `MISSING_PATH_VARIABLE`       | 500    | none         |
| No converter for a property type                                                      | `ConversionNotSupportedException`                                                 | `CONVERSION_NOT_SUPPORTED`    | 500    | none         |
| Response body could not be serialised                                                 | `HttpMessageNotWritableException`                                                 | `MESSAGE_NOT_WRITABLE`        | 500    | none         |
| Exception with a declared status (see [Your own exceptions](#36-your-own-exceptions)) | `ResponseStatusException`, `ErrorResponseException`, `@ResponseStatus` exceptions | `ERROR_RESPONSE`              | varies | none         |
| Any other exception                                                                   | `Exception`                                                                       | `INTERNAL_SERVER_ERROR`       | 500    | none         |

Spring MVC errors keep Spring's own status, `title` and `detail`. The last row uses a generic body and
is logged at `ERROR` with its stack trace, unless your own code handles the exception. You don't need
`spring.mvc.problemdetails.enabled`, and setting it causes no conflict.

Upload limits are set with:

```properties
# Maximum size of a single uploaded file (default: 1MB)
spring.servlet.multipart.max-file-size=10MB
# Maximum size of the entire multipart request (default: 10MB)
spring.servlet.multipart.max-request-size=50MB
```

### 3.6 Your own exceptions

**With the built-in `code`.** These become Problem Details bodies with their declared status and
`code=ERROR_RESPONSE`:

- `ResponseStatusException`
- `ErrorResponseException` and its subclasses. A `code` you set on one is replaced by `ERROR_RESPONSE`.
- exceptions annotated with `@ResponseStatus`, directly or on a superclass. The annotation's `reason`
  becomes `detail`, and is resolved as a message code if one matches.

**With your own `code`.** Write an `@ExceptionHandler` returning a `ProblemDetail`. It takes precedence
over the catch-all, and `traceId` is added for you:

```java
@RestControllerAdvice
class OrderExceptionHandler {

    @ExceptionHandler(OrderClosedException.class)
    ResponseEntity<ProblemDetail> handle(OrderClosedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The order is closed.");
        problem.setProperty("code", "ORDER_CLOSED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
```

**Precedence over this library.** The library's specific handlers run before an advice with no `@Order`.
This applies to any exception they cover, and also to your own exception when one of those is its
*cause*: a `MyException` wrapping a `DataIntegrityViolationException` gets the library's 409, not your
handler for `MyException`. To take over, annotate your advice with `@Order(Ordered.HIGHEST_PRECEDENCE)`,
or [replace the library's handler](#6-disabling-or-replacing-handlers).

## 4 Trace ID

When Micrometer Tracing is on the classpath:

- Problem Details bodies from `@ExceptionHandler` methods get `"traceId": "<hex>"`.
- Responses get an `X-Trace-Id: <hex>` header, success and error alike, including responses written by
  filters that answer the request themselves, such as a rate limiter.

The two are configured separately:

```properties
# Only add the header to error responses (status 400+). Defaults to false.
blocks.error.trace.header.error-only=true

# Statuses that never get the header. Defaults to 401,403, so callers who aren't authenticated or
# authorised aren't handed a trace ID. Leave it empty to add the header regardless of status.
blocks.error.trace.header.exclude-statuses=401,403

# Turn off each one entirely. Both default to true.
blocks.error.trace.body.enabled=false
blocks.error.trace.header.enabled=false
```

**Sampling.** With Brave, every request gets a trace ID, including those that aren't sampled, so
responses carry the ID regardless of the sampling probability. An unsampled ID won't be found in your
tracing backend, though. To look up every request in development:

```properties
# Fraction of requests exported to the tracing backend (0.0–1.0). Defaults to 0.1.
management.tracing.sampling.probability=1.0
```

## 5 Spring Security

The library works alongside Spring Security without depending on it. Who answers depends on where the
request is rejected:

| Situation                                                                     | Who answers     | Response                                                                                   |
|-------------------------------------------------------------------------------|-----------------|--------------------------------------------------------------------------------------------|
| Rejected by the filter chain (`authorizeHttpRequests`, `httpBasic`, …)        | Spring Security | Its own 401 / 403: empty body, no `code`, no `traceId`, no `X-Trace-Id` header             |
| `@PreAuthorize` / `@Secured` denial or `AuthenticationException` in your code | Spring Security | The same 401 / 403, not a 500                                                              |
| Unknown route that your rules require authentication for                      | Spring Security | 401 for anonymous callers. `ROUTE_NOT_FOUND` only appears for routes Security lets through |
| Everything else, for callers Security lets through                            | This library    | The usual Problem Details body                                                             |

Things to be aware of:

- **Security's 401/403 are not Problem Details.** Clients must handle an empty 401/403 body. To get
  Problem Details there too, give `HttpSecurity` your own `AuthenticationEntryPoint` and
  `AccessDeniedHandler`; the library doesn't provide them.
- **The trace ID exclusion applies to every 401/403**, whoever produced it. A 401/403 answered by an
  `@ExceptionHandler` has no header but still has `traceId` in its body. See [Trace ID](#4-trace-id) to
  change the excluded statuses.
- **A wrapped Security exception is a 500.** If another exception wraps an `AccessDeniedException` or
  `AuthenticationException`, it is treated as unexpected. Unwrap it, or handle it with your own
  `@ExceptionHandler`.
- **`/error` must be permitted.** Errors that Spring Boot routes through `/error`, such as failures
  raised inside filters, are subject to your Security rules. Anonymous callers get a 401 instead of the
  real error unless `/error` is permitted. Errors this library handles don't use `/error`.

## 6 Disabling or replacing handlers

Each handler can be switched off with a property. All default to `true`:

| Property                                    | Handler                               | Covers                               |
|---------------------------------------------|---------------------------------------|--------------------------------------|
| `blocks.error.enabled`                      | everything, including the trace ID    |                                      |
| `blocks.error.json.enabled`                 | `JsonExceptionHandler`                | JSON request body errors             |
| `blocks.error.validation.enabled`           | `ValidationExceptionHandler`          | Spring MVC validation                |
| `blocks.error.constraint-violation.enabled` | `ConstraintViolationExceptionHandler` | `ConstraintViolationException`       |
| `blocks.error.data.enabled`                 | `DataExceptionHandler`                | `org.springframework.dao` exceptions |
| `blocks.error.jpa.enabled`                  | `JpaExceptionHandler`                 | JPA exceptions                       |
| `blocks.error.spring-data.enabled`          | `SpringDataExceptionHandler`          | `PropertyReferenceException`         |
| `blocks.error.global.enabled`               | `GlobalExceptionHandler`              | Spring MVC errors and the catch-all  |

Exceptions a disabled handler would have covered fall through to `GlobalExceptionHandler`, or to Spring's
defaults if that is disabled too:

- JSON errors become 400 `MALFORMED_REQUEST_BODY`.
- Spring MVC validation errors become 422 `VALIDATION_FAILED` with Spring's body, without `violations`.
- Data access, JPA and `ConstraintViolationException` errors become 500 `INTERNAL_SERVER_ERROR`.

To customise a handler instead, declare your own bean of the same type, typically a subclass overriding
one method. The library's bean then backs off:

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

## 7 Logging

Each handler logs under its own class name in `io.adzubla.blocks.error.handler`, at a level that reflects
who is at fault:

| Logger                                | Level                                                                                      |
|---------------------------------------|--------------------------------------------------------------------------------------------|
| `JsonExceptionHandler`                | `DEBUG`; `ERROR` with stack trace for a server-side mapping defect                         |
| `ValidationExceptionHandler`          | `DEBUG`; `ERROR` with stack trace for return-value violations                              |
| `ConstraintViolationExceptionHandler` | `DEBUG`; `ERROR` with stack trace for return-value violations                              |
| `DataExceptionHandler`                | `WARN` for integrity violations and optimistic locking failures; `DEBUG` for empty results |
| `JpaExceptionHandler`                 | `DEBUG` for not-found; `WARN` for optimistic locking and entity-exists conflicts           |
| `SpringDataExceptionHandler`          | `DEBUG`                                                                                    |
| `GlobalExceptionHandler`              | `WARN` for 4xx; `ERROR` with stack trace for 5xx and unexpected exceptions                 |

`DataExceptionHandler`'s integrity violation logs include the raw database message, which may contain the
rejected values.

Client errors are logged as a one-line summary. For JSON and validation errors, the stack trace is logged
separately at `TRACE`:

```properties
logging.level.io.adzubla.blocks.error.handler.JsonExceptionHandler=TRACE
logging.level.io.adzubla.blocks.error.handler.ValidationExceptionHandler=TRACE
```

Spring MVC also logs a `WARN` for every 405 under its `PageNotFound` category. To avoid logging them twice:

```properties
logging.level.org.springframework.web.servlet.PageNotFound=ERROR
```

## 8 Internationalisation (i18n)

The library's `title` and `detail` text ships in English (the fallback) and Brazilian Portuguese (`pt_BR`).

### 8.1 Overriding messages and adding a locale

Each message is looked up first in your application's `MessageSource`, then in the library's bundle. Define
a key in your own bundle (by default `messages*.properties`) to reword it, or in a new locale file to add a
language. Keys you don't define keep the library's text.

```properties
# src/main/resources/messages_es.properties
error.validation-failed.title=Validación fallida
error.validation-failed.detail=Uno o más campos no pasaron la validación
error.resource-not-found.title=Recurso no encontrado
error.resource-not-found.detail=El recurso solicitado no fue encontrado.
```

The full list of keys is in `io/adzubla/blocks/error/messages.properties` inside the JAR. In messages that
take arguments (`{0}`), write a literal apostrophe as `''`.

The text of Spring MVC errors is Spring's own. Spring looks it up in your `MessageSource` under
`problemDetail.title.<exception class>` and `problemDetail.<exception class>`.

### 8.2 Locale resolution

The locale comes from the `Accept-Language` header by default. To use a cookie or session instead, declare a
`LocaleResolver` bean.

A header naming a locale with no translation (e.g. `fr`) gets English, never the server's JVM locale. When the
header is absent, Spring uses the JVM default locale. To pin that too:

```properties
spring.web.locale=en
```

### 8.3 Validation constraint messages

`violations[].message` comes from Bean Validation, not from the `MessageSource` above. Hibernate Validator
ships translations for many languages, including Portuguese. To override or add messages, put a
`ValidationMessages_<locale>.properties` file on your classpath:

```properties
# src/main/resources/ValidationMessages_pt_BR.properties
jakarta.validation.constraints.NotBlank.message=não pode estar em branco
jakarta.validation.constraints.Size.message=o tamanho deve estar entre {min} e {max}
```

## 9 Error code reference

| `code`                            | Status | Meaning                                                                      |
|-----------------------------------|--------|------------------------------------------------------------------------------|
| `MALFORMED_JSON`                  | 400    | Syntactically invalid JSON                                                   |
| `MALFORMED_REQUEST_BODY`          | 400    | Body unreadable (unclassified, or missing)                                   |
| `UNKNOWN_JSON_FIELD`              | 400    | Field not declared on the target type, or ignored                            |
| `INVALID_ENUM_VALUE`              | 400    | Value not in enum constants                                                  |
| `INVALID_FIELD_VALUE`             | 400    | Value cannot be converted to the target type                                 |
| `TYPE_MISMATCH`                   | 400    | Wrong JSON token type for target                                             |
| `NULL_VALUE_NOT_ALLOWED`          | 400    | `null` sent for a field that forbids it                                      |
| `INVALID_TYPE_ID`                 | 400    | Unknown polymorphic type id                                                  |
| `INVALID_PROPERTY_REFERENCE`      | 400    | Unknown property in a Spring Data `Sort` (e.g. `?sort=nope`)                 |
| `INTEGER_OVERFLOW`                | 400    | Numeric value outside type range                                             |
| `VALIDATION_FAILED`               | 422    | Bean Validation constraint failure                                           |
| `METHOD_VALIDATION_ERROR`         | 500    | Method validation failure, only when `blocks.error.validation.enabled=false` |
| `DUPLICATE_VALUE`                 | 409    | Unique constraint violated                                                   |
| `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | Foreign key constraint violated                                              |
| `DATA_INTEGRITY_VIOLATION`        | 409    | Other integrity constraint violated                                          |
| `OPTIMISTIC_LOCKING_FAILURE`      | 409    | Resource modified by another request                                         |
| `RESOURCE_NOT_FOUND`              | 404    | Entity or query result not found                                             |
| `METHOD_NOT_ALLOWED`              | 405    | HTTP method not supported                                                    |
| `UNSUPPORTED_MEDIA_TYPE`          | 415    | `Content-Type` not accepted                                                  |
| `NOT_ACCEPTABLE`                  | 406    | Requested `Accept` type unavailable                                          |
| `MISSING_PATH_VARIABLE`           | 500    | Path variable declared on the handler but absent from the mapping            |
| `MISSING_REQUEST_PARAMETER`       | 400    | Required query parameter absent                                              |
| `MISSING_REQUEST_PART`            | 400    | Required multipart part absent                                               |
| `REQUEST_BINDING_ERROR`           | 400    | Other request binding failure (e.g. missing header or cookie)                |
| `ROUTE_NOT_FOUND`                 | 404    | No handler or static resource for the request path                           |
| `REQUEST_TIMEOUT`                 | 503    | Async request timed out                                                      |
| `PAYLOAD_TOO_LARGE`               | 413    | Upload exceeds configured limit                                              |
| `MALFORMED_MULTIPART_REQUEST`     | 400    | Multipart body unparsable, or request not multipart                          |
| `CONVERSION_NOT_SUPPORTED`        | 500    | No converter for property type                                               |
| `PARAMETER_TYPE_MISMATCH`         | 400    | Query/path parameter type coercion failed                                    |
| `MESSAGE_NOT_WRITABLE`            | 500    | Response body could not be serialised                                        |
| `ERROR_RESPONSE`                  | varies | Exception with a declared status (see section 3.6)                           |
| `INTERNAL_SERVER_ERROR`           | 500    | Unexpected exception, or a constraint violation on a return value            |
