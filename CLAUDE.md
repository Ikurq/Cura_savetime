# Claude Code Guidelines

This repository follows AI-Driven Development (AIDD) principles. All guidelines in `AGENTS.md` apply here.

## ⚡ Autonomous Execution (Auto-Pilot)
When given a feature or bug task:
1. **Spec & Plan**: Identify requirements and test cases without asking repetitive questions.
2. **Subagents for Accuracy**: Launch subagents/task agents for deep code research or independent reviews when dealing with complex tasks.
3. **Test First**: Write automated tests in `tests/` before implementation.
4. **Implement**: Write minimal, robust code.
5. **Auto-Verify**: Run `powershell -File ./run.ps1 check` (or `./run.sh check`). Fix any errors until all tests and lint checks pass cleanly.
6. **Report**: Summarize changes and verification status.

## UI/UX & Copywriting (Anti-AI-Slop)
- Never use cheesy emojis (🔥, 🚀, ✨, etc.) or marketing fluff ("🔥急上昇しています", "爆速", "革新的な").
- Use calm, concise, and functional UI text without unnecessary filler.

## Common Task Commands
- Run all checks (format + lint + test): `powershell -File ./run.ps1 check` or `./run.sh check` or `make check`
- Run tests only: `powershell -File ./run.ps1 test` or `./run.sh test`
- Run linter only: `powershell -File ./run.ps1 lint` or `./run.sh lint`
- Run formatter: `powershell -File ./run.ps1 format` or `./run.sh format`
