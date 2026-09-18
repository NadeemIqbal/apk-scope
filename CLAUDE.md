## Project Supervisor Context

Before architectural planning, roadmap changes, implementation of new capabilities, or capability verification, read the following documents:

1. `docs/SUPERVISOR_CONTEXT.md`
2. `docs/PRODUCT_VISION.md`
3. `docs/ARCHITECTURE_CONSTRAINTS.md`
4. `docs/VERIFICATION_STRATEGY.md`
5. `docs/FUTURE_CAPABILITIES.md`

These documents represent the durable architectural and product decisions for this repository.

They take precedence over assumptions made during an individual implementation session.

The `.planning` directory represents the current GSD execution state.

When GSD planning conflicts with an architectural constraint documented above, do not silently override the constraint. Identify the conflict and resolve the planning state.

Do not claim support for a capability unless the evidence requirements in `docs/VERIFICATION_STRATEGY.md` have been satisfied.