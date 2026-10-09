# FlowCatalyst application framework

A Java 25 framework for business applications that run next to the FlowCatalyst platform: modules
and a start-up lifecycle, typed configuration, PostgreSQL with migrations, a use-case write path
with optimistic locking and a transactional outbox, checked SQL, a small HTTP layer on Jetty,
locks, and a FlowCatalyst platform module: the application's service-account client, the sync of
its definitions, and receivers for the dispatch jobs and scheduled-job firings the platform delivers
to it.

The framework is pre-release. **Start with [the guide's status page](guide/STATUS.md).**

## The guide (for application developers)

How to build an application with it. Start at the [guide index](guide/index.md).

- [Getting started](guide/getting-started.md): prerequisites and how to depend on it today
- [Modules and lifecycle](guide/modules-and-lifecycle.md)
- [Config](guide/config.md)
- [Context](guide/context.md)
- [Locks](guide/locks.md)
- [The write path](guide/write-path.md)
- [Checked SQL](guide/checked-sql.md)
- [HTTP](guide/http.md)
- [The FlowCatalyst module](guide/flowcatalyst-module.md)
- [Receivers](guide/receivers.md): dispatch jobs and scheduled-job firings
- [Testing](guide/testing.md)
- [Status and known gaps](guide/STATUS.md)

## The design documents (for contributors)

- [Contracts](guide/reference/contracts.md): the specification of each capability, written before
  the code. [core](contracts/core.md), [database and Redis](contracts/database-and-redis.md),
  [locks](contracts/locks.md), [write path](contracts/write-path.md),
  [checked SQL](contracts/checked-sql.md), [HTTP](contracts/http.md),
  [FlowCatalyst module](contracts/flowcatalyst.md).
- [Framework plumbing](framework-plumbing.md): the design rules and owner rulings.
- [Implementation plan](implementation-plan.md): milestones, order of work, platform work needed.
- [Laravel capability inventory](laravel-capability-inventory.md): the capability survey the design
  started from.
