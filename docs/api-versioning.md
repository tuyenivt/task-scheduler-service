# API Versioning

This service uses URI-path versioning. Every public route is prefixed with
`/api/vN/` where `N` is an integer; today the only version is `v1`. This
document spells out what counts as breaking, how `v2` would coexist with `v1`,
and how callers are warned before a version is removed.

## What requires a new major version

A change is **breaking** and requires `v2` (or `v3`, etc.) when any of the
following are true for an existing route:

- A field is removed from a response body, or its type changes.
- A field becomes required on a request body where it was previously optional.
- A validation rule becomes stricter in a way that rejects previously-accepted
  input (eg. lowering a `@Size` cap on a list, narrowing a regex).
- An HTTP status code changes for the same input.
- The shape of a JSON envelope (`ApiResponse`, `BatchCreateResult`,
  `BulkCancelResult`) changes its top-level keys.
- An enum value is removed from an input or output. (Adding new enum values is
  permitted - see below.)

Everything else is **additive** and can ship inside the current major version:

- Adding a new optional request field.
- Adding a new field to a response body.
- Adding a new enum value to a response (clients should treat unknown enums
  gracefully).
- Adding a new endpoint.
- Loosening validation (raising a `@Max` cap, accepting more input).
- Improving error messages while keeping HTTP status codes stable.

## Parallel versions

When `v2` ships, `v1` continues to serve traffic from the **same** application
deployment. There is no fork: the controllers for both versions live in the
codebase and dispatch to the same domain services where possible.

- New code lives in `controller/v2/...` and reuses the same `TaskManagementService`
  whenever the underlying behavior is unchanged.
- Where v1 and v2 disagree on a contract (eg. v2 changes the bulk-cancel result
  shape), a thin adapter at the controller layer transforms between v1 DTOs and
  the shared service result.
- `springdoc` exposes both versions under `/api-docs` so the Swagger UI lists
  them side by side.

## Deprecation timeline

Once a successor version is generally available:

| Phase               | Duration  | Behavior |
|---------------------|-----------|----------|
| **Announced**       | -         | Release notes name the new version; v1 keeps working unchanged. |
| **Soft deprecation**| 90 days   | v1 responses include `Deprecation: true` and `Sunset: <RFC 9745 date>` headers and a `Link` header pointing to this document. The Swagger UI tag reads "(deprecated)". |
| **Hard deprecation**| 90 days   | v1 still serves traffic but logs a WARN per request and increments a counter (`task_scheduler_api_deprecated_requests_total{version}`) so operators can identify holdout callers from metrics. |
| **Removal**         | -         | v1 routes return `410 Gone` with a body pointing at the successor. |

Minimum window between Announced and Removal: **180 days**. This is enough for
quarterly-release callers to migrate without an emergency cut.

## Patch-level changes

We do not version patch-level changes (`v1.2`, etc.) in the URI. Bug fixes that
preserve the contract roll out behind the existing `v1`. Behavioral fixes that
*do* change observable behavior (eg. a bug that returned 500 now correctly
returns 400) are documented in release notes and treated as a soft contract
shift inside the current major version - clients depending on the old
(buggy) behavior should be a known-rare case.

## Operational headers

The service may emit the following response headers on any version:

- `X-API-Version: v1` - the version that served the request.
- `Deprecation: true` - this version is soft- or hard-deprecated.
- `Sunset: Sun, 01 Sep 2026 00:00:00 GMT` - the planned removal date.
- `Link: <https://repo/docs/api-versioning.md>; rel="sunset"` - pointer here.

Callers should log unexpected `Deprecation` headers loudly and open a
migration ticket.
