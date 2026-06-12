# Adding a new TaskType / TaskStatus value

The `scheduled_tasks` table guards `task_type`, `status`, and `priority` with
`CHECK` constraints (see `V1__Create_task_scheduler_tables.sql`). The enums in
`domain/enums/` and these constraints must stay in lockstep - inserting a value
the constraint does not list fails with a `23514 check_violation`.

Because the constraint is checked on **write**, a new enum value must exist in
the database constraint *before* any instance running the new code can insert
it. That makes this an **expand-then-deploy** change.

## Recipe (expand-then-deploy)

Follow these steps in order. Skipping the ordering risks a window where a
new-code instance inserts a value the database still rejects.

### 1. Expand the constraint (migration ships first)

Add a Flyway migration that swaps the `CHECK` constraint for one that also
permits the new value. Run this migration **before** rolling out the code that
emits the new value. Both old and new code tolerate the widened constraint -
old code simply never inserts the new value.

```sql
-- V<n>__add_task_type_subscription_renew.sql
-- Expand chk_task_type to permit the new SUBSCRIPTION_RENEW type.
-- Deploy this migration BEFORE the code that creates SUBSCRIPTION_RENEW tasks.

ALTER TABLE scheduled_tasks
    DROP CONSTRAINT chk_task_type;

ALTER TABLE scheduled_tasks
    ADD CONSTRAINT chk_task_type CHECK (task_type IN (
        'ORDER_CANCEL', 'PAYMENT_REFUND', 'PAYMENT_PARTIAL_REFUND',
        'PAYMENT_VOID', 'WEBHOOK_NOTIFICATION', 'CUSTOM',
        'SUBSCRIPTION_RENEW'  -- new value
    ));
```

`DROP` + `ADD` runs in one transaction under Flyway, so there is no instant
where the table has no constraint. On large tables the `ADD CONSTRAINT` takes a
brief `ACCESS EXCLUSIVE` lock while it validates existing rows; existing rows
already satisfy the superset constraint, so validation is fast, but schedule it
outside peak write windows for very large tables.

### 2. Add the enum value in code

```java
public enum TaskType {
    // ... existing values ...
    SUBSCRIPTION_RENEW("subscription-renew", "Subscription Renewal");
}
```

### 3. Add the handler

Create a `@Component implements TaskHandler` returning the new `getTaskType()`.
It auto-registers via `TaskHandlerRegistry` at `@PostConstruct`.

### 4. Deploy the code

Now roll out the application. Instances can safely create and execute the new
task type because the constraint (step 1) already permits it.

## Removing an enum value (contract phase)

Removal is the reverse and is **riskier** - never tighten a `CHECK` constraint
while rows containing the value still exist, and never deploy code that drops an
enum value the database can still contain.

1. Deploy code that no longer **creates** the value (it may still read it).
2. Wait until no live rows hold the value (let them reach a terminal state, or
   migrate them).
3. Ship a migration that tightens the constraint to drop the value.
4. Only then remove the enum constant from code.

## Status values

`status` follows the same pattern via `chk_status`. Adding a status is rarer -
it usually means a new lifecycle state, which also touches
`TaskStatus.isExecutable()` / `isTerminal()` / `isFailure()` and the polling
query's status filter (`findTasksForExecution` and `acquireTaskLock`). Audit
those call sites in the same change.
