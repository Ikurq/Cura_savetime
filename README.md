# AI駆動開発 (AI-Driven Development) スターターキット

このリポジトリは、あらゆる言語や新規・既存プロジェクトに**コピー＆ペーストして流用できる、汎用AI駆動開発の土台テンプレート**です。

Google Antigravity、Cursor、Claude Code、GitHub Copilot などの主要AIコーディングツールに対応し、**自律テスト・品質検証（自動Lint/Formatter）・仕様駆動開発**の標準基盤を提供します。

---

## 📁 リポジトリ全体構成

```text
├── .github/
│   ├── workflows/ci.yml           # GitHub Actions CI自動化（テスト & Lint）
│   ├── copilot-instructions.md    # GitHub Copilot 用指示書
│   └── pull_request_template.md   # PR作成テンプレート（品質チェックリスト付き）
├── docs/
│   ├── WORKFLOW.md                # AI駆動開発ワークフロー (Spec -> Tasks -> TDD)
│   └── templates/
│       ├── SPEC_TEMPLATE.md       # 機能仕様書テンプレート
│       └── TASK_LIST_TEMPLATE.md  # タスク分解リストテンプレート
├── templates/                     # 言語別即応設定テンプレート
│   ├── python/                    # Python用 (pytest + ruff)
│   └── typescript/                # TypeScript用 (vitest + Biome)
├── AGENTS.md                      # Antigravity / 汎用AIエージェント向け指示規約
├── CLAUDE.md                      # Claude Code 向け指示規約
├── .cursorrules                   # Cursor 向け指示規約
├── .editorconfig                  # エディタ共通フォーマット設定
├── .gitignore                     # Git除外設定
├── run.ps1                        # 共通コマンドランナー (PowerShell / Windows)
├── run.sh                         # 共通コマンドランナー (Bash / Linux・Mac)
├── Makefile                       # 共通タスク定義 (make test, make check 等)
└── README.md                      # 本ドキュメント
```

---

## ⚡ 統一タスクコマンド（ワンコマンドインターフェース）

言語に関わらず、AIおよび開発者は以下の統一コマンドで操作できます。

| タスク | PowerShell (Windows) | Bash (Mac/Linux) | Make |
| :--- | :--- | :--- | :--- |
| **一括検証** | `.\run.ps1 check` | `./run.sh check` | `make check` |
| **テスト実行** | `.\run.ps1 test` | `./run.sh test` | `make test` |
| **Lint検証** | `.\run.ps1 lint` | `./run.sh lint` | `make lint` |
| **自動整形** | `.\run.ps1 format` | `./run.sh format` | `make format` |

---

## 🔄 別のプロジェクトに応用・流用する手順

新しいプロジェクトを立ち上げる、または既存プロジェクトに導入する際の手順です：

### ステップ 1: ファイル群を新プロジェクトへコピー
このリポジトリから、以下のコアファイルをコピーします：
- `AGENTS.md`, `CLAUDE.md`, `.cursorrules`, `.github/`
- `docs/`
- `run.ps1`, `run.sh`, `Makefile`, `.editorconfig`, `.gitignore`

### ステップ 2: 言語テンプレートの選択
- **Python の場合**: `templates/python/` 内の `pyproject.toml` をルートに配置
- **TypeScript の場合**: `templates/typescript/` 内の `package.json`, `tsconfig.json` をルートに配置

### ステップ 3: 開発開始
1. `docs/WORKFLOW.md` を開き、AIに「この機能の仕様書を `docs/specs/` に作って」と指示
2. 仕様合意後、「タスクリストに分解して1つずつテスト駆動で実装して」と指示
3. AIが自動で `.\run.ps1 check` を回しながら安全に実装を完了
