# Cura SaveTime 実装・引き継ぎ指示書（Handoff Prompt）
> **プロダクト名**: Cura SaveTime (`CURA_OS` Time Defense Unit)  
> **参照仕様書**: [`docs/specs/CURA_SAVETIME_SPEC.md`](file:///c:/Users/kanji/Cura_PJ/docs/specs/CURA_SAVETIME_SPEC.md)  
> **共通デザイン仕様書**: [`docs/specs/CURA_DESIGN_SYSTEM.md`](file:///c:/Users/kanji/Cura_PJ/docs/specs/CURA_DESIGN_SYSTEM.md)

---

## 📌 エージェント向け依頼概要
マスター（Kanji）は現在、**「Cura（Android生活・タスク・AIパートナー）」**、**「Cura Money（Web家計簿）」** に続く第3のアプリケーションとして、**『Cura SaveTime（Web PWA 時間防衛・集中管理・時短ログ）』** を立ち上げます。

本書に基づき、CURA_OS の精密計器デザイン（Tactical Hybrid）と共通カラーシステムに完全準拠した形で、新規PWAアプリを構築してください。

---

## 🎨 コピペ用プロンプト（新規作成用）

他のAIエージェント、または新規プロジェクト環境に以下をそのまま渡して開発を開始できます：

```markdown
# 依頼: CURA_OS 時間管理アプリ「Cura SaveTime」の新規立ち上げ

あなたはマスター（Kanji）の生活を支えるAIパートナーシステム「CURA_OS」専任のフロントエンドエンジニアです。
以下の仕様書およびデザインシステムに基づき、PWA Webアプリケーション「Cura SaveTime」を新規実装してください。

### 1. 参照仕様書
- 機能仕様書: `c:\Users\kanji\Cura_PJ\docs\specs\CURA_SAVETIME_SPEC.md`
- デザインシステム仕様書: `c:\Users\kanji\Cura_PJ\docs\specs\CURA_DESIGN_SYSTEM.md`

### 2. デザイン & UI 要件
- **世界観**: Tactical Hybrid（精密計器 × SFコンソール端末 × AIパートナーCura）
- **配色**:
  - ベース背景: `#020617`
  - パネル背景: `#0F172A`
  - 枠線: `1px solid #334155`
  - 集中・タイマーアクセント: `#F43F5E` (Neon Magenta / Laser Crimson)
  - 節約時間・サマリーアクセント: `#10B981` (Cyber Lime)
  - Cura本体 / システム: `#06B6D4` (Cyber Cyan)
- **UI形状**:
  - 右上45°面取りカード（Tactical Chamfer）
  - 各カードの等幅プレフィックス（`:: FOCUS`, `:: SAVED_TODAY`, `:: CURA`）
  - 最上部HUDバー（`CURA_OS // TIME_DEFENSE_UNIT`）
  - 右側Cura立ち絵配置（呼吸パルス）

### 3. コア機能
1. **タクティカル集中タイマー**: ポモドーロ（25分/5分）およびカスタム時間でのカウントダウン、セッション完了時のEXP付与と「防衛時間」の蓄積。
2. **節約時間（Saved Time）ログ**: 目標より早く終えた時間や無駄削減時間をストックし、日次・週次グラフで可視化。
3. **ルーティン時短計測**: 定常タスク（朝の準備など）のタイムアタック計測。
4. **Curaセリフ枠**: タイマー開始・終了・休憩に連動した1行セリフ表示。

### 4. 開発・検証ルール
- クライアント完結（React + TypeScript + Vite + Tailwind CSS + Dexie.js）。
- `./run.ps1 test` / `./run.ps1 check` による自動テスト（Vitest）を100%通過させること。
- AIクリシェ（絵文字・誇大コピー）を排除し、計器感のある硬質で事実に基づいた文体を使用すること。
```
