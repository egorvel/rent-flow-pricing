## Project map

**Domain:**
Pricing service. Calculates rental price based on the daily price, discount, deposit, and rental period.

**Pricing Entity**
- Serial Number – unique, manually assigned.
- Price – per day.
- Weekend rate – rate applied to the price when the rental is on a weekend (e.g., 0.75, 1, 1.25).
- Long-rental condition – how long the equipment is rented for to qualify for the long-rental discount.
- Long-rental discount – discount applied to the price when the long-rental condition met (e.g., 0.1, 0.25, 0.5).
- Deposit – security deposit.

**Specs:**
- `.specs/<feature>/requirements.md` - what and why. Contain sections Context, User stories with AC, Out of scope, Open/Resolved questions.
- `.specs/<feature>/design.md` - how. API contract, data model, validation rules, invariants, edge cases, integration.
- `.specs/<feature>/tasks.md` - in what order. Decomposition into safe increments with refs to requirements.md and design.md, explicit and testable DoD, dependencies between tasks. Each task: one safe commit.
- spec is the source of truth: gaps go to spec first, code second.
- acceptance criteria must be written in EARS-style.
- Open questions are resolved in `.specs/<feature>/{design,tasks}.md`; `requirements.md` renames `## Open questions` → `## Resolved questions` with each resolution inlined and a pointer to the file where the decision is justified.
- **Always ask 5–7 clarifying questions** before creating or updating any of `.specs/<feature>/{requirements,design,tasks}.md`. One decision = one question. If there is no real doubt, do not invent one.

## Invariants

- **Before declaring done: run `mvn -B -ntp verify` locally and confirm `BUILD SUCCESS`.** Do not split this into "just the tests" or "just compile". If Spotless fails, run `mvn spotless:apply` and re-run `verify`. Never skip with `-DskipTests`, `-Dspotless.check.skip`, or similar flags.
- Prefer existing project patterns over new abstractions. Before adding a new abstraction, dependency, folder, framework, or test style, search for an existing equivalent in the repo.
- Make the smallest change that correctly solves the task. Do not refactor unrelated code, reformat entire files, rename public APIs, or clean up nearby code unless the task explicitly asks for it.
- Do not make tests pass by weakening assertions, deleting tests, ignoring exceptions, increasing timeouts blindly, or suppressing errors. If a test is wrong, explain why and update it to assert the correct behavior.
- Never print, copy, commit, or expose secrets.

## Architectural rules

**Technology requirements:**
- Java 25, Spring Boot 4, Maven
- PostgreSQL 18.3, Flyway
- Spring Data JPA (Hibernate) for persistence
- Testcontainers

**Persistence conventions:**
- Schema changes go through Flyway migrations (`src/main/resources/db/migration/V{n}__description.sql` or `src/main/java/com/rentflow/db/migration/V{n}__description.java`).
- Migrations are append-only too: never edit a shipped migration — add a new one.

**Testing conventions:**
- Unit tests – no Spring context.
- Use Mockito for mocks.
- Integration tests with Testcontainers (real DBs).
- ArchUnit layer boundary test based on the **Layout**.

**Layout**
- Java configs: : `src/main/java/com/rentflow/config`
- Controllers: `src/main/java/com/rentflow/controller`
- Converters: `src/main/java/com/rentflow/converter`
- DTOs: `src/main/java/com/rentflow/dto`
- Services: `src/main/java/com/rentflow/service`
- Repositories: `src/main/java/com/rentflow/repository`
- Entities: `src/main/java/com/rentflow/model`
- Utils: `src/main/java/com/rentflow/util`
- Specs: `.specs/<feature>/{requirements,design,tasks}.md`