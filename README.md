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

`code` is the stable field to branch on (see the [guide for API consumers](#10-guide-for-api-consumers)).
`title` and `detail` are for people and can be [translated or reworded](#8-internationalisation-i18n).

Two kinds of error don't use this format: the 401/403 responses written by
[Spring Security](#5-spring-security), and errors that don't reach an `@ExceptionHandler`, such as an
exception in a servlet filter or a call to `response.sendError`, which Spring Boot's error controller
renders.

### 3.2 JSON request body errors

The Exception column shows the Jackson 3 failure where there is one. Spring delivers most of these as
the cause of an `HttpMessageNotReadableException`.

| Situation                                                | Exception                                                       | `code`                   | Status | Extra fields                                          |
|----------------------------------------------------------|-----------------------------------------------------------------|--------------------------|--------|-------------------------------------------------------|
| Syntactically invalid JSON                               | `StreamReadException`                                           | `MALFORMED_JSON`         | 400    | `line`, `column`                                      |
| Unknown field (see below)                                | `PropertyBindingException`                                      | `UNKNOWN_JSON_FIELD`     | 400    | `path`, `validValues` (the known fields)              |
| Value not in an enum                                     | `InvalidFormatException`                                        | `INVALID_ENUM_VALUE`     | 400    | `path`, `invalidValue`, `expectedType`, `validValues` |
| Value can't be converted to the field's type             | `InvalidFormatException`                                        | `INVALID_FIELD_VALUE`    | 400    | `path`, `invalidValue`, `expectedType`                |
| The target type's constructor rejects the value          | `ValueInstantiationException`                                   | `INVALID_FIELD_VALUE`    | 400    | `path`, `expectedType`                                |
| Wrong JSON token, e.g. an object for a string            | `MismatchedInputException`                                      | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| `null` or no value for a primitive such as `int`         | `MismatchedInputException`                                      | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| A blank body, or several JSON values in one body         | `MismatchedInputException`                                      | `TYPE_MISMATCH`          | 400    | `path` (`$`), `expectedType`                          |
| `null` with `@JsonSetter(nulls = Nulls.FAIL)`            | `InvalidNullException`                                          | `NULL_VALUE_NOT_ALLOWED` | 400    | `path`                                                |
| Unknown polymorphic type id                              | `InvalidTypeIdException`                                        | `INVALID_TYPE_ID`        | 400    | `path`, `invalidValue`, `expectedType`                |
| Number outside the range of its integer type             | `InputCoercionException`                                        | `INTEGER_OVERFLOW`       | 400    | `expectedType`, `validRange`, `line`, `column`        |
| Anything else, e.g. an empty body                        | `HttpMessageNotReadableException` with any other cause, or none | `MALFORMED_REQUEST_BODY` | 400    | none                                                  |
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
- **`invalidValue`**: the rejected value as a string, or JSON `null` if the value was `null`. Only simple
  values are returned, such as strings, numbers, booleans, dates and enums. It is absent for a collection,
  array or object, including a class-level or cross-parameter constraint, so their contents are not returned.
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
| A nested object as a whole               | A class-level constraint on `address`       | `$.address`        |

Paths from a service layer are best-effort: a service can't know how its parameters map to the
request, so it assumes the validated object is the request body.

**Sensitive fields.** `invalidValue` is also absent when the `path` contains one of these words, ignoring
case, `-` and `_`, so `$.newPassword`, `$.client_secret` and `$.credentials.pin` are all covered. Setting
the property replaces the defaults, and leaving it empty returns every simple value:

```properties
blocks.error.validation.sensitive-fields=password,passwd,pwd,secret,token,apikey,privatekey,credential
```

This only applies to `violations`. A JSON error can still echo a value that couldn't be converted, such as
a malformed `UUID` in `$.token`, and a database error can echo a stored value (see
[Data access errors](#34-data-access-errors)).

**Cross-parameter constraints may not run on their own.** On a controller, or on a service with
`adapt-constraint-violations=true`, Spring only raises an exception when a parameter also has a violation.
If the cross-parameter constraint is the only failure, the method runs as if the input were valid. Don't
rely on one there for a check that must hold. On a service with the default setting it is always reported.

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

| Situation                                                                                                         | Exception                                                                         | `code`                        | Status | Extra fields |
|-------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|-------------------------------|--------|--------------|
| HTTP method not supported                                                                                         | `HttpRequestMethodNotSupportedException`                                          | `METHOD_NOT_ALLOWED`          | 405    | none         |
| `Content-Type` not accepted                                                                                       | `HttpMediaTypeNotSupportedException`                                              | `UNSUPPORTED_MEDIA_TYPE`      | 415    | none         |
| Requested `Accept` type unavailable                                                                               | `HttpMediaTypeNotAcceptableException`                                             | `NOT_ACCEPTABLE`              | 406    | none         |
| No handler or static resource for the path                                                                        | `NoResourceFoundException`, `NoHandlerFoundException`                             | `ROUTE_NOT_FOUND`             | 404    | none         |
| Required query parameter absent                                                                                   | `MissingServletRequestParameterException`                                         | `MISSING_REQUEST_PARAMETER`   | 400    | none         |
| Required multipart part absent                                                                                    | `MissingServletRequestPartException`                                              | `MISSING_REQUEST_PART`        | 400    | none         |
| Other binding failure, e.g. missing header or cookie                                                              | `ServletRequestBindingException`                                                  | `REQUEST_BINDING_ERROR`       | 400    | none         |
| Query or path parameter of the wrong type                                                                         | `TypeMismatchException`                                                           | `PARAMETER_TYPE_MISMATCH`     | 400    | none         |
| Upload exceeds the configured limit                                                                               | `MaxUploadSizeExceededException`                                                  | `PAYLOAD_TOO_LARGE`           | 413    | none         |
| Multipart body unparsable, or request not multipart                                                               | `MultipartException`                                                              | `MALFORMED_MULTIPART_REQUEST` | 400    | none         |
| Async request timed out                                                                                           | `AsyncRequestTimeoutException`                                                    | `REQUEST_TIMEOUT`             | 503    | none         |
| Path variable declared but absent from the mapping                                                                | `MissingPathVariableException`                                                    | `MISSING_PATH_VARIABLE`       | 500    | none         |
| No converter for a property type                                                                                  | `ConversionNotSupportedException`                                                 | `CONVERSION_NOT_SUPPORTED`    | 500    | none         |
| Response body could not be serialised                                                                             | `HttpMessageNotWritableException`                                                 | `MESSAGE_NOT_WRITABLE`        | 500    | none         |
| Exception with a declared status (see [Exceptions with a declared status](#93-exceptions-with-a-declared-status)) | `ResponseStatusException`, `ErrorResponseException`, `@ResponseStatus` exceptions | `ERROR_RESPONSE`              | varies | none         |
| Any other exception                                                                                               | `Exception`                                                                       | `INTERNAL_SERVER_ERROR`       | 500    | none         |

Spring MVC errors keep Spring's own status, `title` and `detail`, except `MALFORMED_MULTIPART_REQUEST`,
which has the library's status and text. The last row uses a generic body and is logged at `ERROR` with its
stack trace, unless [your own code handles the exception](#9-custom-error-handling). You don't need
`spring.mvc.problemdetails.enabled`, and setting it causes no conflict.

Upload limits are set with:

```properties
# Maximum size of a single uploaded file (default: 1MB)
spring.servlet.multipart.max-file-size=10MB
# Maximum size of the entire multipart request (default: 10MB)
spring.servlet.multipart.max-request-size=50MB
```

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
| Rejected by the filter chain (`authorizeHttpRequests`, `httpBasic`, …)        | Spring Security | Its own 401 / 403: no `code`, no `traceId`, no `X-Trace-Id` header                         |
| `@PreAuthorize` / `@Secured` denial or `AuthenticationException` in your code | Spring Security | The same 401 / 403, not a 500                                                              |
| Unknown route that your rules require authentication for                      | Spring Security | 401 for anonymous callers. `ROUTE_NOT_FOUND` only appears for routes Security lets through |
| Everything else, for callers Security lets through                            | This library    | The usual Problem Details body                                                             |

Things to be aware of:

- **Security's 401/403 are not Problem Details.** The body is empty if `/error` isn't permitted, and Spring
  Boot's JSON error body (`status`, `error`, `path`) if it is. To get Problem Details there too, give
  `HttpSecurity` your own `AuthenticationEntryPoint` and `AccessDeniedHandler`; the library doesn't provide
  them.
- **The trace ID exclusion applies to 401/403 responses**, whoever produced them. A 401/403 answered by an
  `@ExceptionHandler` has no header but still has `traceId` in its body. See [Trace ID](#4-trace-id) to
  change the excluded statuses.
- **A wrapped Security exception is a 500.** If another exception wraps an `AccessDeniedException` or
  `AuthenticationException`, it is treated as unexpected. Unwrap it, or handle it with your own
  `@ExceptionHandler`.
- **`/error` must be permitted.** Errors that Spring Boot routes through `/error`, such as failures
  raised inside filters, are subject to your Security rules. Anonymous callers get a 401 instead of the
  real error unless `/error` is permitted, and that 401 does have the `X-Trace-Id` header. Errors this
  library handles don't use `/error`.

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
- Spring MVC validation errors become 422 `VALIDATION_FAILED` with Spring's body, without `violations`. A
  `MethodValidationException` becomes 500 `METHOD_VALIDATION_ERROR`, a code that only occurs in this setup.
- Data access, JPA, `PropertyReferenceException` and `ConstraintViolationException` errors become 500
  `INTERNAL_SERVER_ERROR`.

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

These traces can include rejected values, even for [sensitive fields](#33-validation-errors), so avoid `TRACE`
in production.

Spring MVC also logs a `WARN` for every 405 under its `PageNotFound` category. To avoid logging them twice:

```properties
logging.level.org.springframework.web.servlet.PageNotFound=ERROR
```

## 8 Internationalisation (i18n)

The library's `title` and `detail` text ships in English (the fallback) and Brazilian Portuguese (`pt_BR`).
Only `pt-BR` gets Portuguese: `pt` and `pt-PT` get English.

### 8.1 Overriding messages and adding a locale

Each message is looked up first in your application's `MessageSource`, then in the library's bundle. Define
a key in your own bundle (by default `messages*.properties`) to reword it, or in a new locale file to add a
language. Keys you don't define keep the library's text.

**A base `messages.properties` must exist**, even if empty. Without it Spring Boot doesn't create a
`MessageSource`, and your locale files are silently ignored.

```properties
# src/main/resources/messages_es.properties
error.validation-failed.title=Validación fallida
error.validation-failed.detail=Uno o más campos no pasaron la validación
error.resource-not-found.title=Recurso no encontrado
error.resource-not-found.detail=El recurso solicitado no fue encontrado.
```

The full list of keys is in `io/adzubla/blocks/error/messages.properties` inside the JAR. In messages that
take arguments (`{0}`), write a literal apostrophe as `''`.

The text of Spring MVC errors, other than `MALFORMED_MULTIPART_REQUEST`, is Spring's own. Spring looks it up
in your `MessageSource` under `problemDetail.title.<exception class>` and `problemDetail.<exception class>`.

### 8.2 Locale resolution

The locale comes from the `Accept-Language` header by default. To use a cookie or session instead, declare a
`LocaleResolver` bean named `localeResolver`. A bean with another name is ignored.

A header naming a locale with no translation (e.g. `fr`) gets the library's English text, not its text for the
server's JVM locale. Your own bundle behaves differently: by default, Spring Boot falls back to the JVM locale
there, so on a `pt_BR` server, `fr` gets the keys you defined in `messages_pt_BR.properties`. When the header is
absent, Spring uses the JVM default locale. To get English in both cases:

```properties
spring.messages.fallback-to-system-locale=false
spring.web.locale=en
```

### 8.3 Validation constraint messages

`violations[].message` comes from Bean Validation, not from the library's keys above. Hibernate Validator
ships translations for many languages, including Portuguese. To override or add messages, put a
`ValidationMessages_<locale>.properties` file on your classpath. Spring Boot also resolves a `{key}` in a
constraint message from your `MessageSource`, so those keys can live in `messages*.properties` instead.

```properties
# src/main/resources/ValidationMessages_pt_BR.properties
jakarta.validation.constraints.NotBlank.message=não pode estar em branco
jakarta.validation.constraints.Size.message=o tamanho deve estar entre {min} e {max}
```

## 9 Custom error handling

An exception from your own business logic is a generic 500 `INTERNAL_SERVER_ERROR` unless you handle it.
To answer it in the same format as every other error, write an `@ExceptionHandler` that returns a
`ProblemDetail` with a `code` property. `code` is a plain string, so your codes need no registration.

### 9.1 Handling a single exception

```java
@RestControllerAdvice
class OrderExceptionHandler {

    @ExceptionHandler(OrderClosedException.class)
    ResponseEntity<ProblemDetail> handle(OrderClosedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The order is closed.");
        problem.setTitle("Order closed");
        problem.setProperty("code", "ORDER_CLOSED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
```

The library adds `traceId` and the `X-Trace-Id` header, and Spring adds `instance`:

```json
{
  "title": "Order closed",
  "status": 409,
  "detail": "The order is closed.",
  "instance": "/orders/42/items",
  "code": "ORDER_CLOSED",
  "traceId": "69fcf2db21f488679d633abb34871dbb"
}
```

Without `setTitle`, `title` is the HTTP status text, such as `Conflict`.

### 9.2 Handling many exceptions with a base class

For several business exceptions, give them a base class that carries the code, the status and the message
arguments, and write one handler for all of them:

```java
public abstract class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Object[] args;

    protected BusinessException(String code, HttpStatus status, String message, Object... args) {
        super(message);
        this.code = code;
        this.status = status;
        this.args = args;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public Object[] getArgs() { return args; }
}

public class OrderClosedException extends BusinessException {

    public OrderClosedException(long orderId) {
        super("ORDER_CLOSED", HttpStatus.CONFLICT, "Order " + orderId + " is closed.", String.valueOf(orderId));
    }
}
```

```java
@RestControllerAdvice
class BusinessExceptionHandler {

    private final MessageSource messageSource;

    BusinessExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handle(BusinessException ex) {
        var locale = LocaleContextHolder.getLocale();
        var key = "error." + ex.getCode().toLowerCase(Locale.ROOT).replace('_', '-');
        // Not ex.getMessage() as the default: with arguments, it would lose its apostrophes to MessageFormat.
        var detail = messageSource.getMessage(key + ".detail", ex.getArgs(), null, locale);

        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), detail != null ? detail : ex.getMessage());
        problem.setTitle(messageSource.getMessage(key + ".title", null, ex.getStatus().getReasonPhrase(), locale));
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }
}
```

The text is translated like the library's own, from your message bundle, and the keys follow the library's
style: `ORDER_CLOSED` uses `error.order-closed.title` and `error.order-closed.detail`. A key you don't define
falls back to the exception's message and the HTTP status text. As in
[Overriding messages](#81-overriding-messages-and-adding-a-locale), a base `messages.properties` must exist:

```properties
# src/main/resources/messages_pt_BR.properties
error.order-closed.title=Pedido encerrado
error.order-closed.detail=O pedido {0} está encerrado.
```

Pass numbers as strings, as `OrderClosedException` does, because message formatting would otherwise render
`1234567` as `1,234,567`.

### 9.3 Exceptions with a declared status

If you don't need your own code, these become Problem Details bodies with their declared status and
`code=ERROR_RESPONSE`:

- `ResponseStatusException`
- `ErrorResponseException` and its subclasses. A `code` you set on one is replaced by `ERROR_RESPONSE`.
- exceptions annotated with `@ResponseStatus`, directly or on a superclass. The annotation's `reason`
  becomes `detail`, and is resolved as a message code if one matches.

### 9.4 Precedence over this library

Your handlers run before the library's catch-all, with no `@Order` needed. There are two things to watch for:

- **The library's specific handlers run first.** This applies to any exception they cover, and also to your
  own exception when one of those is its *cause*: a `MyException` wrapping a
  `DataIntegrityViolationException` gets the library's 409, not your handler for `MyException`. To take over,
  annotate your advice with `@Order(Ordered.HIGHEST_PRECEDENCE)`, or
  [replace the library's handler](#6-disabling-or-replacing-handlers).
- **Don't handle `Exception.class` yourself.** With no `@Order`, such a handler also takes the Spring MVC
  errors, so a 405 or an unknown route would get your response instead of the library's. Handle your own
  exception types and leave the rest to the catch-all.

### 9.5 Good practices

- **Keep codes stable.** A code is part of your API's contract: use `UPPER_SNAKE_CASE`, give each code one
  meaning, and don't rename it once clients depend on it.
- **Reuse a library code when the meaning is the same.** For a missing resource, use
  `ErrorCode.RESOURCE_NOT_FOUND.name()` rather than inventing a new code.
- **Choose the status by who must act.** Use 404 for a missing resource, 409 for a conflict with the current
  state, 422 for values that break a rule, and 5xx only for faults on the server.
- **Write `detail` for the client.** Don't include stack traces, SQL, internal identifiers or the message of a
  third-party exception. Log those instead.
- **Log by fault.** Log client errors as a one-line summary at `DEBUG` or `WARN`, and server faults at `ERROR`
  with the stack trace. The library doesn't log exceptions your handler takes.
- **Reuse the library's extra fields.** Use `path`, `invalidValue`, `validValues` and `violations` with the
  same meaning they have elsewhere. A business rule on a field can use the validation format, so clients
  handle both the same way:

  ```java
  var status = HttpStatus.UNPROCESSABLE_CONTENT;
  var problem = ProblemDetail.forStatusAndDetail(status, "One or more fields failed validation");
  problem.setTitle("Validation failed");
  problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
  problem.setProperty("violations", List.of(Map.of(
          "path", "$.quantity",
          "invalidValue", "500",
          "message", "exceeds the stock available")));
  return ResponseEntity.status(status).body(problem);
  ```

- **Document your codes.** Add them to your copy of the [guide for API consumers](#10-guide-for-api-consumers).

## 10 Guide for API consumers

The subsections below are written for your API's clients, so you can copy them into its documentation.

### 10.1 Error response shape

Errors follow [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) and are sent with
`Content-Type: application/problem+json`:

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

These fields are part of every error, except where marked optional:

| Field      | Type   | Presence | Description                                                 |
|------------|--------|----------|-------------------------------------------------------------|
| `code`     | string | Always   | Stable identifier of the error. Use this field in your code |
| `status`   | number | Always   | HTTP status, same as the response status                    |
| `title`    | string | Always   | Short summary for people. May be translated                 |
| `detail`   | string | Optional | Explanation for people. May be translated                   |
| `instance` | string | Always   | Path of the request                                         |
| `traceId`  | string | Optional | ID to quote when reporting a problem                        |

These fields are all optional, and appear only for some codes (see [Error codes](#103-error-codes)):

| Field          | Type             | Presence | Description                                                   |
|----------------|------------------|----------|---------------------------------------------------------------|
| `path`         | string           | Optional | JSONPath of the field in the request, e.g. `$.address.street` |
| `invalidValue` | string           | Optional | The rejected value. Always a string, e.g. `"-1"`              |
| `expectedType` | string           | Optional | Name of the type the API expected                             |
| `validValues`  | array of strings | Optional | The accepted values, or the known field names                 |
| `validRange`   | string           | Optional | The accepted numeric range, e.g. `[-2147483648, 2147483647]`  |
| `line`         | number           | Optional | Line of the problem in the request body                       |
| `column`       | number           | Optional | Column of the problem in the request body                     |
| `violations`   | array of objects | Optional | Every validation failure in the request (see below)           |

Each `violations` entry has:

| Field          | Type           | Presence | Description                                                                                      |
|----------------|----------------|----------|--------------------------------------------------------------------------------------------------|
| `path`         | string         | Always   | Location in the request: a body field, or a query or path parameter. `$` means the whole request |
| `invalidValue` | string or null | Optional | The rejected value, `null` if the value was null, and absent for a list, object or secret        |
| `message`      | string         | Always   | Why the value was rejected. May be translated                                                    |

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

### 10.2 Handling errors

- **Use `code` in your logic, not the text.** `title`, `detail` and `message` can change and depend on the
  language. Send `Accept-Language` to ask for a language; the API uses a default language if it isn't available.
- **Expect new codes.** An API can add its own codes, so fall back to the HTTP status for a code you don't
  recognise.
- **Don't assume every error has this body.** 401 and 403 responses can be empty or use another format, and
  so can some other errors. Check that the `Content-Type` is `application/problem+json` before parsing.
- **Quote the trace ID when reporting a problem.** Use `traceId` from the body, or the `X-Trace-Id` response
  header. The header can also appear on successful responses, and is usually absent from 401 and 403.

### 10.3 Error codes

Extra fields are included when the API has the information, so treat them as optional even for a code
that lists them.

**Request body.** Fix the request before sending it again.

| Situation                                       | `code`                   | Status | Extra fields                                          |
|-------------------------------------------------|--------------------------|--------|-------------------------------------------------------|
| The body isn't valid JSON                       | `MALFORMED_JSON`         | 400    | `line`, `column`                                      |
| The body is empty or can't be read              | `MALFORMED_REQUEST_BODY` | 400    | none                                                  |
| The body is blank, or has several JSON values   | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| The body has a field the API doesn't accept     | `UNKNOWN_JSON_FIELD`     | 400    | `path`, `validValues`                                 |
| A value isn't one of the accepted values        | `INVALID_ENUM_VALUE`     | 400    | `path`, `invalidValue`, `expectedType`, `validValues` |
| A value can't be converted to the expected type | `INVALID_FIELD_VALUE`    | 400    | `path`, `invalidValue`, `expectedType`                |
| A value has the wrong JSON type                 | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| A required number or boolean is null or missing | `TYPE_MISMATCH`          | 400    | `path`, `expectedType`                                |
| A field that can't be null is null              | `NULL_VALUE_NOT_ALLOWED` | 400    | `path`                                                |
| The type name of an object is unknown           | `INVALID_TYPE_ID`        | 400    | `path`, `invalidValue`, `expectedType`                |
| A number is too large or small for its type     | `INTEGER_OVERFLOW`       | 400    | `expectedType`, `validRange`, `line`, `column`        |

**Validation.** Fix every entry in `violations` before sending the request again.

| Situation                      | `code`              | Status | Extra fields |
|--------------------------------|---------------------|--------|--------------|
| One or more values are invalid | `VALIDATION_FAILED` | 422    | `violations` |

**Request format.** Fix the request before sending it again.

| Situation                                                     | `code`                        | Status | Extra fields   |
|---------------------------------------------------------------|-------------------------------|--------|----------------|
| No such route                                                 | `ROUTE_NOT_FOUND`             | 404    | none           |
| The route doesn't support this HTTP method                    | `METHOD_NOT_ALLOWED`          | 405    | none           |
| The API can't produce the type asked for in `Accept`          | `NOT_ACCEPTABLE`              | 406    | none           |
| The API doesn't accept this `Content-Type`                    | `UNSUPPORTED_MEDIA_TYPE`      | 415    | none           |
| A required query parameter is missing                         | `MISSING_REQUEST_PARAMETER`   | 400    | none           |
| A query or path parameter has the wrong type                  | `PARAMETER_TYPE_MISMATCH`     | 400    | none           |
| The request can't be bound, e.g. a required header is missing | `REQUEST_BINDING_ERROR`       | 400    | none           |
| The sort parameter names an unknown property                  | `INVALID_PROPERTY_REFERENCE`  | 400    | `invalidValue` |
| A required part of a multipart request is missing             | `MISSING_REQUEST_PART`        | 400    | none           |
| The multipart request can't be read, or isn't multipart       | `MALFORMED_MULTIPART_REQUEST` | 400    | none           |
| The upload is too large                                       | `PAYLOAD_TOO_LARGE`           | 413    | none           |

**Resource state.** The request is well formed, but conflicts with the data the API holds. Reload the
resource and try again after `OPTIMISTIC_LOCKING_FAILURE`; otherwise, change the request.

| Situation                                            | `code`                            | Status | Extra fields   |
|------------------------------------------------------|-----------------------------------|--------|----------------|
| The resource doesn't exist                           | `RESOURCE_NOT_FOUND`              | 404    | none           |
| A resource with the same value already exists        | `DUPLICATE_VALUE`                 | 409    | `invalidValue` |
| The request references a resource that doesn't exist | `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | `invalidValue` |
| The resource is still referenced by other resources  | `REFERENTIAL_INTEGRITY_VIOLATION` | 409    | `invalidValue` |
| The change breaks another data rule                  | `DATA_INTEGRITY_VIOLATION`        | 409    | none           |
| Another request changed the resource first           | `OPTIMISTIC_LOCKING_FAILURE`      | 409    | none           |

**Server errors.** The request may be fine, and resending it unchanged may not help. Report the problem with
the trace ID, except for `REQUEST_TIMEOUT`, which you can retry later.

| Situation                                 | `code`                     | Status | Extra fields |
|-------------------------------------------|----------------------------|--------|--------------|
| An unexpected error occurred              | `INTERNAL_SERVER_ERROR`    | 500    | none         |
| The API can't process this route          | `MISSING_PATH_VARIABLE`    | 500    | none         |
| The API can't convert a value it received | `CONVERSION_NOT_SUPPORTED` | 500    | none         |
| The API can't write the response          | `MESSAGE_NOT_WRITABLE`     | 500    | none         |
| The request took too long to process      | `REQUEST_TIMEOUT`          | 503    | none         |

**API-specific errors.** The status and meaning depend on the endpoint, so see its documentation.

| Situation                              | `code`           | Status | Extra fields |
|----------------------------------------|------------------|--------|--------------|
| An error raised by the API's own logic | `ERROR_RESPONSE` | varies | none         |
