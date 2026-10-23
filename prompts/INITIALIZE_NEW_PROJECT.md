# 新プロジェクト初期化用プロンプト

新しいプロジェクトフォルダでAI（Antigravity, Cursor, Claude Code等）を開き、以下のプロンプトをそのまま送信してください。

```markdown
AI駆動開発のテンプレートリポジトリから、開発基盤ファイル一式をこのプロジェクトにコピーして初期セットアップしてください。

■ コピー元ディレクトリ:
`c:\Users\kanji\AI駆動`

■ 実行指示:
1. コピー元から以下のファイル・フォルダを現在のプロジェクトのルートにコピーしてください：
   - AI規約: `AGENTS.md`, `CLAUDE.md`, `.cursorrules`
   - ガイド・仕様書テンプレート: `docs/`, `prompts/`, `README.md`
   - コマンドランナー & 設定: `run.ps1`, `run.sh`, `Makefile`, `.editorconfig`, `.gitignore`
   - CI設定: `.github/`
   - 言語別テンプレート: `templates/`
   ※ 注意: `.git`, `.venv`, `__pycache__`, `.pytest_cache` はコピーしないでください。

2. 【Python開発の場合】
   `templates/python/` の設定ファイルやテストをルートに展開し、仮想環境作成と依存関係（pytest, ruff）を導入してください。

   【TypeScript開発の場合】
   `templates/typescript/` の設定ファイルやテストをルートに展開し、`npm install` を実行してください。

3. 最後に `.\run.ps1 check`（または `./run.sh check`）を実行し、初期テストとLintがすべてGreen（合格）になることを確認して報告してください。
```
