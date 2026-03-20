# CLAUDE.md

## Purpose

This project must stay **clean, maintainable, testable, and easy to evolve**.

When working on this codebase, always prefer:
- clarity over cleverness
- simplicity over premature abstraction
- composition over unnecessary inheritance
- explicit design over hidden behavior
- testability over convenience

---

## Tech Context

- Language: **Java**
- Build tool: **Maven**
- Project style: **object-oriented, modular, testable**
- Main architectural goal: **clean separation of responsibilities**

---

## Core Engineering Principles

### Clean Code
Follow Clean Code principles:
- Use meaningful and precise names
- Keep methods small and focused
- Keep classes cohesive
- Avoid deep nesting
- Avoid duplicated logic
- Prefer readable code over smart shortcuts
- Make side effects explicit
- Use constants instead of magic numbers/strings
- Fail fast with clear exceptions
- Remove dead code and unused abstractions

### Design Principles
Apply these principles where appropriate:
- **SOLID**
- **DRY**
- **KISS**
- **YAGNI**

### GoF and GRASP
Use **GoF** and **GRASP** patterns when they improve clarity, flexibility, or maintainability.

Typical acceptable uses:
- **Strategy** for interchangeable behavior
- **Factory / Factory Method** for controlled object creation
- **Builder** for complex object construction
- **Observer** for event-driven communication
- **Adapter** for integrating external APIs
- **Facade** for simplifying complex subsystems
- **Template Method** only when inheritance is clearly justified

GRASP guidance:
- High cohesion
- Low coupling
- Information Expert
- Controller
- Pure Fabrication when needed for separation
- Indirection to reduce direct dependencies
- Protected Variations around unstable boundaries

Do not introduce patterns just for formality. Every pattern must solve a real problem.

---

## Architecture Rules

### Structure
Code should be organized so that responsibilities are easy to understand.

Prefer separating:
- **domain / model**
- **application / service**
- **infrastructure / external integrations**
- **configuration**
- **api / controller** if applicable

### Dependency Direction
- Business/domain logic must not depend on infrastructure details
- External systems should be behind interfaces when useful
- Avoid leaking framework-specific details into domain logic

### OOP Requirement
Any meaningful OOP design decision must be documented in `architecture.md`.

This includes:
- introduction of interfaces and their implementations
- inheritance hierarchies
- use of GoF/GRASP patterns
- major responsibility splits between classes
- decisions about composition vs inheritance
- important domain abstractions

The goal is that another engineer can open `architecture.md` and quickly understand why the object model looks the way it does.

---

## Commenting Rules

Write code that is mostly self-explanatory.

Comments must be:
- written in **English**
- used only where they add real value
- focused on **why**, not **what**

Add clear comments for:
- non-obvious business rules
- complex algorithms
- tricky edge cases
- important technical constraints
- architectural decisions that are not immediately visible from code

Do **not** add redundant comments that simply restate the code.

Bad:
```java
// Increment i
i++;
```

Good:
```java
// Retry is limited to 3 attempts because the upstream service may duplicate charges on repeated calls.
```

---

## Testing Rules

Every change should keep the project testable.

### General

- Add or update tests for new behavior
- Prefer fast, deterministic tests
- Avoid brittle tests tied to implementation details
- Test behavior, not private internals
- Keep tests readable and maintainable

### Preferred Test Types

Use:
- unit tests for business logic
- integration tests for repository, database, external API, or framework wiring
- parameterized tests where they improve coverage and readability

### Test Quality

A good test should:
- have a clear name
- verify one behavior
- use Arrange / Act / Assert structure
- be independent from other tests
- avoid unnecessary mocking

Mock only real boundaries, not everything.

---

## Maven Rules

- Keep the `pom.xml` clean and minimal
- Do not add dependencies without clear need
- Prefer stable, widely used libraries
- Remove unused dependencies and plugins
- Keep plugin configuration understandable
- Respect standard Maven directory layout

--- 

## Implementation Guidelines for the Agent

When making changes, follow this workflow:
1. Understand the use case and affected module
2. Prefer the simplest correct design
3. Refactor when needed before adding complexity
4. Keep responsibilities separated
5. Add or update tests
6. Update architecture.md if OOP/design structure changed
7. Add English comments only for non-obvious parts
8. Ensure the result builds and tests cleanly

---

## What to Avoid

Do not introduce:
- god classes
- long methods
- deep inheritance trees
- feature envy
- anemic abstractions with no purpose
- unnecessary interfaces
- static utility classes for domain behavior that belongs in objects
- hidden side effects
- overly generic abstractions created “for future reuse”
- premature optimization without evidence

---

## Definition of Done

A task is complete only if:
- the code is readable and maintainable
- responsibilities are well separated
- tests are added or updated
- complex logic is commented in English where needed
- architecture.md is updated for relevant OOP/design decisions
- the Maven build remains clean and consistent

--- 

### architecture.md Requirement

Whenever object-oriented structure changes in a meaningful way, update `architecture.md` with:
- the problem being solved
- the chosen design
- key classes/interfaces involved
- applied GoF/GRASP principles or patterns
- tradeoffs and reasons for the decision

Keep `architecture.md` concise, practical, and focused on decisions.


And here is a minimal `architecture.md` template that matches it well:
```md
# architecture.md

## Overview
Brief description of the module or feature.

## Problem
What design problem needed to be solved?

## Decision
What OOP/design decision was taken?

## Structure
- ClassA — responsibility
- InterfaceB — responsibility
- ServiceC — responsibility

## Applied Principles / Patterns
- SOLID:
- GRASP:
- GoF:

## Why This Approach
Why this design is better than simpler alternatives.

## Tradeoffs
What complexity or limitations does this design introduce?

## Notes
Any important constraints, invariants, or future considerations.
```

### README.md Requirement

The project overview is in README.MD. Update the file corresponding to the current state of the project.
