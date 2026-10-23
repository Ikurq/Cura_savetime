# CURA SAVETIME 仕様書・要件定義書 (v1.0)
> **Code Name**: Tactical Chrono / CURA_OS Time Defense Unit  
> **Status**: Approved Specification  
> **Target Platforms**: Web (React / TypeScript / Tailwind CSS / PWA)  
> **Ecosystem**: CURA_OS Family (`Cura` Android / `Cura Money` Web / `Cura SaveTime` Web)

---

## 1. プロジェクト概要

- **プロダクト名**: Cura SaveTime（キュラ セーブタイム）
- **テーマ**: 時間防衛・集中管理・時短ログ（Time Defense & Optimization Unit）
- **世界観**: 
  - `CURA_OS` 第3のモジュール。
  - お金（Cura Money）を節約・防衛するように、マスターの有限な**「時間（24時間）」を無駄遣いから防衛し、集中と自立を支える**。
- **利用想定環境**: スマートフォン（PWA ホーム画面追加）および PCブラウザ
- **運用要件**: 完全クライアントサイド完結（0円運用、IndexedDB / Dexie.js、オフライン動作）

---

## 2. デザインシステム準拠（CURA_OS Tactical Hybrid）

[`CURA_DESIGN_SYSTEM.md`](file:///c:/Users/kanji/Cura_PJ/docs/specs/CURA_DESIGN_SYSTEM.md) に完全準拠する。

### 2.1 カラーパレット
- **ベース背景**: `#020617` (Deep Navy Black)
- **パネルサーフェス**: `#0F172A` (半透明 `rgba(15, 23, 42, 0.85)`)
- **入力・行背景**: `#1E293B`
- **境界線**: `1px solid #334155` (Steel Line)
- **テーマアクセントカラー**:
  - **集中・タイマー・行動（Primary Accent）**: `#F43F5E` / `#FB7185` (Neon Magenta / Laser Crimson) - `:: FOCUS`, `:: TIMER`
  - **時間・節約・実績（Secondary Accent）**: `#10B981` / `#84CC16` (Cyber Lime) - `:: SAVED_TIME`, `:: RECORD`
  - **Cura本体 / システム（System Accent）**: `#06B6D4` / `#22D3EE` (Cyber Cyan) - `:: CURA`, `:: SYSTEM`

### 2.2 形状・UIルール
- **Tactical Chamfer Card**: 各情報カードの右上角を 45° 面取りカット（10px〜12px）。
- **プレフィックス**: すべてのモジュール見出しに `:: FOCUS`, `:: SAVED_TODAY`, `:: CURA` 等の等幅プレフィックスを付与。
- **HUDヘッダー**: 画面最上部に `CURA_OS // TIME_DEFENSE_UNIT v1.0` のデジタルインジケーターを常駐。
- **セグメントメーター**: 集中ゲージや残り時間はブロック式インジケーター（`[ ■■■■■□□□□□ ]`）で可視化。

---

## 3. コア機能要件

### 3.1. タクティカル・集中タイマー (Tactical Focus Timer)
- **ポモドーロ & カスタムセッション**:
  - デフォルト: 25分（集中） + 5分（休息）。
  - カスタム: 15分 / 45分 / 60分 / 90分 プリセット。
- **セッション中の計器表示**:
  - 中央に大画面ミリ秒/秒カウントダウン（等幅フォント）。
  - 周囲に45°面取りの進行度サークルまたはタクティカルバー。
  - セッション対象タスクの選択（「レポート執筆」「プログラミング」「講義課題」など）。
- **集中完了報酬**:
  - セッション完走時に「集中防衛時間（例: 25分）」を記録。
  - Curaの経験値（EXP）が加算され、セリフが変化。

### 3.2. 時間家計簿・無駄時間削減ログ (Time Expenditure & Saving)
- **24時間のタイムスライス記録**:
  - 1日の時間を「投資（集中・勉強）」「維持（睡眠・食事・移動）」「浪費（SNS巡回・無目的ネット）」に分類。
- **節約時間（Saved Time）の算出**:
  - 普段ダラダラしてしまう行動をタイマーやタイムボックスで早期終了させた際の「浮いた時間」をストック。
  - 例: 「目標より20分早く準備完了 → 20分 Save!」「SNSリミッターで30分削減 → 30分 Save!」
- **日次・週次サマリー**:
  - 本日の防衛時間（集中時間合計）。
  - 今週の累計セーブ時間（時・分）。

### 3.3. ルーティン時短アタック (Routine Speedrun)
- 朝の身支度、外出準備、掃除などのルーティンをストップウォッチ計測。
- 過去の平均タイムと比較し、短縮できた時間を「節約時間（Saved Time）」として計上。

### 3.4. Cura AIパートナー演出 (Cura Interaction)
- **立ち絵配置**: 画面右側にCura（透過PNG）を配置、呼吸パルスと浮遊パーティクル。
- **音声・セリフ枠**:
  - タイマー開始時: `「集中セッション開始。余計な割り込みはキュラが遮断します」`
  - タイマー完了時: `「セッション完了を確認。マスター、素晴らしい集中力でした」`
  - 休憩中: `「5分間のクールダウンです。水分補給を忘れないでください」`
  - VOICEVOX音声再生（将来のネイティブ/API連携対応）。

---

## 4. データモデル (Data Schema - IndexedDB / Dexie)

```typescript
export interface FocusSession {
  id?: string;
  taskTitle: string;
  category: 'study' | 'work' | 'routine' | 'creative';
  targetDurationMinutes: number; // 目標時間（分）
  actualDurationMinutes: number; // 実績時間（分）
  isCompleted: boolean;
  startedAt: string; // ISO 8601
  endedAt?: string;
  earnedExp: number;
}

export interface SavedTimeRecord {
  id?: string;
  date: string; // YYYY-MM-DD
  savedMinutes: number; // 節約できた分数
  reason: string; // "朝の身支度を短縮", "タイマー集中で前倒し完了" など
  category: 'focus' | 'routine' | 'limiter';
}

export interface RoutineItem {
  id?: string;
  title: string;
  standardDurationMinutes: number; // 基準目安時間
  bestRecordMinutes?: number;
  order: number;
}
```

---

## 5. 技術選定 & アーキテクチャ

| 領域 | 選定技術 |
| :--- | :--- |
| **フロントエンド** | React 18 + Vite + TypeScript |
| **スタイリング** | Tailwind CSS (CURA_OS カラーパレット) |
| **アイコン** | lucide-react |
| **状態・ローカルDB** | Dexie.js (IndexedDB) + dexie-react-hooks |
| **音響効果** | Web Audio API (計器クリック音・完了チャイム) |
| **PWA化** | vite-plugin-pwa (オフライン対応、ホーム画面追加) |
| **品質検証基盤** | Vitest + Biome + `./run.ps1 check` |
