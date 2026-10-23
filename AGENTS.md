# AI Agent Instructions & Guidelines

You are an autonomous AI coding assistant working in this repository. Follow these strict guidelines:

## ⚡ Autonomous Execution (Auto-Pilot Rule)
When the user gives a brief instruction (e.g. "Create feature X", "Fix bug Y", "〇〇作って"):
**DO NOT stop to ask trivial questions.** Execute the complete cycle autonomously:
1. **Spec & Plan**: Briefly define the spec and test cases (or draft `docs/specs/<feature>.md` if non-trivial).
2. **Test First**: Write comprehensive automated tests in `tests/` covering normal, edge, and error cases.
3. **Implement**: Implement the minimal, clean code to pass tests.
4. **Self-Verify**: Automatically run `./run.ps1 check` (or `./run.sh check` / `make check`). If tests or lint fail, fix them iteratively until 100% green.
5. **Report**: Present the final diff and test results cleanly.

## 1. Quality & Verification Rules
- **Run Tests**: After modifying any code, execute `./run.ps1 test` (or `./run.sh test`). Never mark a task as completed without verifying that tests pass.
- **Run Linter & Formatter**: Always run `./run.ps1 format` and `./run.ps1 lint` before finishing your edits. Ensure 0 lint errors and clean formatting.
- **Run All Checks**: Use `./run.ps1 check` (or `./run.sh check` / `make check`) to verify formatting, linting, and tests in one step.
- **Do Not Break Existing Functionality**: Maintain backwards compatibility unless explicitly requested otherwise.
- **Follow Workflow**: For non-trivial features, follow the workflow outlined in `docs/WORKFLOW.md` (Spec -> Tasks -> TDD).

## 2. Coding Standards
- Write clean, self-documenting code with meaningful variable and function names.
- Keep functions small, modular, and single-purpose.
- Ensure strict type safety (use types wherever supported).
- Preserve existing comments and docstrings unless directly superseded.

## 3. Proactive Subagent Utilization (Accuracy Boost)
- **Delegate Exploration & Research**: Actively launch subagents for deep codebase searches, dependency investigations, and web research to keep the main context focused and clean.
- **Independent Verification & Review**: For non-trivial features or complex logic, invoke subagents to act as independent reviewers or edge-case testers to rigorously inspect code before finalization.
- **Parallel Task Execution**: Break down independent investigation or validation tasks across subagents to maximize accuracy and throughput.

## 4. UI/UX & Copywriting Standards (No "AI Slop")
- **Eliminate AI Clichés & Emojis**: Strictly NEVER use cheap hype emojis (e.g. 🔥, 🚀, ✨, 💡, 🎉) or exaggerated marketing buzzwords (e.g. "🔥急上昇しています", "🚀爆速", "✨次世代の", "革新的な").
- **Concise & Functional Copy**: Write clear, direct, and factual UI copy. Avoid marketing fluff and unnecessary commentary. (e.g. Use "トレンド" or "閲覧数順" instead of "🔥いま話題沸騰中！").
- **Clean Design**: Build minimal, purposeful UI. Avoid decorative bloat that exists only to fill space.

## 5. Communication & Execution Style
- Prioritize making targeted edits over full file rewrites.
- Explain non-obvious implementation decisions concisely.
- If a requirement or edge case is ambiguous, clarify or state reasonable assumptions upfront.
