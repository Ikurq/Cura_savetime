# GitHub Copilot Instructions

## Project Context
This is an AI-Driven Development repository. Follow these principles:

- Standard task commands:
  - Check everything: `./run.ps1 check` (Windows) / `./run.sh check` (Unix) / `make check`
  - Tests: `./run.ps1 test` / `./run.sh test`
  - Lint: `./run.ps1 lint` / `./run.sh lint`
  - Format: `./run.ps1 format` / `./run.sh format`

## Quality Rules
- Write automated tests for all new functions and bug fixes.
- Strictly adhere to typing and clean code standards.
- Refer to `docs/WORKFLOW.md` for feature implementation phases.
