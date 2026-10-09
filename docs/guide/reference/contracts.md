<!-- types: Module, Context, LockStore, UseCases, Routes, FlowCatalystModule, CheckedSql, Database -->
# Contracts

The contracts in [`docs/contracts/`](../../contracts/) are the specification. Each capability is
specified there **before** it is built, and the code is a port of that text. This guide is the
how-to; when you need the exact rule, read the contract.

Contracts can lag the code. Differences found when this guide was last verified are listed at the
end of [STATUS](../STATUS.md#where-the-guide-and-the-contracts-can-differ).

| Contract | Covers | Guide page |
|---|---|---|
| [core](../../contracts/core.md) | modules and the application lifecycle, named instances and drivers, `Context`, config, commands | [Modules and lifecycle](../modules-and-lifecycle.md), [Config](../config.md), [Context](../context.md) |
| [database and Redis](../../contracts/database-and-redis.md) | database instances, migrations, Redis connections | [Modules and lifecycle](../modules-and-lifecycle.md#the-database-module-and-migrations), [Config](../config.md) |
| [locks](../../contracts/locks.md) | keys, leases, fencing tokens, `withLock`, the three drivers, the contract test suite | [Locks](../locks.md) |
| [write path](../../contracts/write-path.md) | aggregates, repositories, use cases, optimistic locking, events, the outbox, ids | [The write path](../write-path.md) |
| [checked SQL](../../contracts/checked-sql.md) | query files, types, generated code, the Maven plugin | [Checked SQL](../checked-sql.md) |
| [HTTP](../../contracts/http.md) | routing, middleware, validation, errors, Jetty, Datastar | [HTTP](../http.md) |
| [FlowCatalyst module](../../contracts/flowcatalyst.md) | the platform client, definition sync, outbox delivery stance, receivers | [The FlowCatalyst module](../flowcatalyst-module.md), [Receivers](../receivers.md) |

Design documents behind the contracts:

- [`framework-plumbing.md`](../../framework-plumbing.md): the design rules and owner rulings.
- [`implementation-plan.md`](../../implementation-plan.md): milestones and the order of work.
- [`laravel-capability-inventory.md`](../../laravel-capability-inventory.md): the capability
  inventory the design started from.
