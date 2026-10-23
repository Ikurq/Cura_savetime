#!/usr/bin/env bash
# タスク実行スクリプト (Bash)
# 使い方: ./run.sh test | lint | format | check

set -euo pipefail

TASK="${1:-check}"

show_help() {
    echo "=========================================="
    echo " AI駆動開発 共通コマンドランナー (Bash)"
    echo "=========================================="
    echo "使用方法: ./run.sh [タスク名]"
    echo ""
    echo "タスク一覧:"
    echo "  test     : すべての自動テストを実行"
    echo "  lint     : 静的解析・スタイル検証を実行"
    echo "  format   : コードの自動整形を実行"
    echo "  check    : formatチェック + lint + test を一括実行"
    echo "  help     : このヘルプを表示"
    echo ""
}

PYTEST_CMD="pytest"
RUFF_CMD="ruff"
if [ -d ".venv" ]; then
    [ -f ".venv/bin/pytest" ] && PYTEST_CMD=".venv/bin/pytest"
    [ -f ".venv/bin/ruff" ] && RUFF_CMD=".venv/bin/ruff"
fi

case "$TASK" in
    test)
        echo "==> テストを実行中..."
        if [ -f "pyproject.toml" ]; then
            $PYTEST_CMD -v
        elif [ -f "package.json" ]; then
            npm test
        fi
        ;;
    lint)
        echo "==> Lintを実行中..."
        if [ -f "pyproject.toml" ]; then
            $RUFF_CMD check .
        elif [ -f "package.json" ]; then
            npm run lint
        fi
        ;;
    format)
        echo "==> コード自動整形を実行中..."
        if [ -f "pyproject.toml" ]; then
            $RUFF_CMD format .
            $RUFF_CMD check --fix .
        elif [ -f "package.json" ]; then
            npm run format
        fi
        ;;
    check)
        echo "==> 全品質チェック (Format / Lint / Test) を実行中..."
        if [ -f "pyproject.toml" ]; then
            $RUFF_CMD format --check .
            $RUFF_CMD check .
            $PYTEST_CMD
        elif [ -f "package.json" ]; then
            npm run lint
            npm test
        fi
        echo "==> すべてのチェックに合格しました！"
        ;;
    *)
        show_help
        ;;
esac
