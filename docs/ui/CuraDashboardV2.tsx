import React, { useState } from "react";

// ============================================================================
// CURA_OS // TIME_DEFENSE_MONITOR (v2.0)
// Refactored Mobile Dashboard UI (Width: 390px Standard / Cyber-OS Dark Theme)
// ============================================================================

type TabId = "home" | "logs" | "settings";

interface AppUsageSummary {
  id: string;
  name: string;
  category: string;
  totalSeconds: number;
  isGhost?: boolean;
  isExcluded?: boolean;
  color: string;
}

interface LogEntry {
  id: string;
  time: string;
  appName: string;
  packageName: string;
  durationSeconds: number;
  isGhost?: boolean;
}

const HOURLY_TREND_SECONDS = [
  120, 45, 0, 0, 0, 0, 180, 740, 1420, 960, 610, 1680,
  1920, 840, 520, 1100, 1540, 1890, 2100, 1750, 2340, 1980, 920, 310,
];

const INITIAL_TOP_APPS: AppUsageSummary[] = [
  { id: "com.android.chrome", name: "Chrome", category: "Browser", totalSeconds: 5656, color: "#38BDF8" },
  { id: "com.google.android.youtube", name: "YouTube", category: "Video", totalSeconds: 4320, color: "#F43F5E" },
  { id: "com.twitter.android", name: "X (Twitter)", category: "Social", totalSeconds: 2910, color: "#00E676" },
  { id: "com.miHoYo.GenshinImpact", name: "原神", category: "Game", totalSeconds: 1680, isGhost: true, color: "#F59E0B" },
  { id: "com.discord", name: "Discord", category: "Comm", totalSeconds: 614, color: "#A855F7" },
];

const INITIAL_LOGS: LogEntry[] = [
  { id: "1", time: "21:42:10", appName: "Chrome", packageName: "com.android.chrome", durationSeconds: 916 },
  { id: "2", time: "21:41:58", appName: "Pixel Launcher", packageName: "com.google.android.apps.nexuslauncher", durationSeconds: 2 },
  { id: "3", time: "21:41:55", appName: "システム UI", packageName: "com.android.systemui", durationSeconds: 3 },
  { id: "4", time: "21:18:05", appName: "YouTube", packageName: "com.google.android.youtube", durationSeconds: 1430 },
  { id: "5", time: "21:17:59", appName: "設定", packageName: "com.android.settings", durationSeconds: 4 },
  { id: "6", time: "20:59:12", appName: "原神", packageName: "com.miHoYo.GenshinImpact", durationSeconds: 1120, isGhost: true },
  { id: "7", time: "20:45:00", appName: "X (Twitter)", packageName: "com.twitter.android", durationSeconds: 845 },
  { id: "8", time: "20:44:56", appName: "One UI ホーム", packageName: "com.sec.android.app.launcher", durationSeconds: 1 },
  { id: "9", time: "20:32:10", appName: "Discord", packageName: "com.discord", durationSeconds: 614 },
];

/**
 * 時間表記の簡素化ヘルパー
 * `76秒 (1m16s)` のような重複併記を廃止し、`1分16秒` / `1時間34分` に一本化
 */
export function formatDurationClean(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds));
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  const s = safe % 60;
  if (h > 0) return m > 0 ? `${h}時間${m}分` : `${h}時間`;
  if (m > 0) return s > 0 ? `${m}分${s}秒` : `${m}分`;
  return `${s}秒`;
}

export default function CuraDashboardV2() {
  const [activeTab, setActiveTab] = useState<TabId>("home");
  const [daemonActive, setDaemonActive] = useState<boolean>(true);
  const [autoGameGhost, setAutoGameGhost] = useState<boolean>(true);
  const [microLogsOpen, setMicroLogsOpen] = useState<boolean>(false);
  const [selectedApp, setSelectedApp] = useState<{ name: string; packageName: string; isGhost?: boolean } | null>(null);
  const [blockCountdown, setBlockCountdown] = useState<number | null>(null);

  // 5秒以上と5秒未満の極小ログに分離
  const mainLogs = INITIAL_LOGS.filter((l) => l.durationSeconds >= 5);
  const microLogs = INITIAL_LOGS.filter((l) => l.durationSeconds < 5);
  const microTotalSeconds = microLogs.reduce((acc, item) => acc + item.durationSeconds, 0);

  const totalUsageSeconds = 15180; // 04:13:00
  const goalUsagePercent = 80; // 1時間予算 30分中 24分使用

  const startThirtySecondBlock = () => {
    setSelectedApp(null);
    setBlockCountdown(30);
  };

  return (
    <div className="min-h-screen bg-[#070A0E] text-[#E6EDF3] flex justify-center items-start py-4 font-sans select-none">
      {/* モバイル画面基準コンテナ (幅 390px) */}
      <div className="w-[390px] min-h-[844px] bg-[#0D1117] border border-[#30363D] rounded-2xl shadow-2xl flex flex-col overflow-hidden relative">

        {/* 1. ステータスヘッダー */}
        <header className="bg-[#161B22] border-b border-[#30363D] px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="font-mono text-[11px] font-bold tracking-wider text-[#38BDF8]">
              CURA_OS // TIME_DEFENSE_MONITOR
            </span>
          </div>
          <div className="flex items-center gap-2">
            <span
              className={`font-mono text-[10px] font-bold px-2 py-0.5 rounded border ${
                daemonActive
                  ? "bg-[#00E676]/10 text-[#00E676] border-[#00E676]/40"
                  : "bg-[#21262D] text-[#8B949E] border-[#30363D]"
              } flex items-center gap-1.5`}
            >
              <span
                className={`w-1.5 h-1.5 rounded-full ${
                  daemonActive ? "bg-[#00E676] animate-pulse" : "bg-[#8B949E]"
                }`}
              />
              {daemonActive ? "ACTIVE" : "STANDBY"}
            </span>
          </div>
        </header>

        {/* メインコンテンツ領域 */}
        <main className="flex-1 overflow-y-auto p-3.5 space-y-3.5">
          {activeTab === "home" && (
            <>
              {/* 2. 本日の利用時間 */}
              <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[11px] font-bold text-[#38BDF8]">
                    本日の利用時間
                  </span>
                  <span className="font-mono text-[10px] text-[#00E676]">
                    [ 計測中: Chrome 15分16秒 ]
                  </span>
                </div>

                {/* 本日の合計スマホ利用時間（ボールド・等幅） */}
                <div className="flex items-baseline justify-between">
                  <div className="font-mono text-4xl font-bold tracking-tight text-[#E6EDF3]">
                    04:13:00
                  </div>
                  <span className="font-mono text-xs text-[#8B949E]">
                    目標上限: 05:00:00
                  </span>
                </div>

                {/* 目標利用時間に対するプログレスバー */}
                <div className="space-y-1.5 pt-1">
                  <div className="flex justify-between font-mono text-[11px]">
                    <span className="text-[#8B949E]">1時間予算 (30分枠)</span>
                    <span className="text-[#F59E0B] font-bold">{goalUsagePercent}% 使用済み (残6分)</span>
                  </div>
                  <div className="w-full h-2 bg-[#21262D] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-gradient-to-r from-[#00E676] via-[#F59E0B] to-[#F43F5E] rounded-full transition-all"
                      style={{ width: `${goalUsagePercent}%` }}
                    />
                  </div>
                </div>

                {/* キャラクター連動カード（通信ウィンドウ / アシスタント枠として統合） */}
                <div className="mt-3 bg-[#21262D]/90 border border-[#30363D] rounded-lg p-3 flex items-center gap-3">
                  <div className="w-11 h-11 rounded-lg bg-[#0D1117] border border-[#38BDF8]/40 flex items-center justify-center shrink-0 font-mono text-xs text-[#38BDF8] font-bold">
                    CURA
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-mono text-[9.5px] font-bold text-[#38BDF8] tracking-wide">
                      :: CURA_COMM_LINK // ASSISTANT
                    </div>
                    <p className="text-xs text-[#E6EDF3] leading-relaxed mt-0.5">
                      「マスター、Chromeを15分見てますよ！残り枠はあと15分ですっ。一度目を休めませんか？」
                    </p>
                  </div>
                  <button
                    onClick={startThirtySecondBlock}
                    className="shrink-0 bg-[#F43F5E] hover:bg-[#F43F5E]/90 text-white font-mono text-[10px] font-bold px-2.5 py-2 rounded-md transition"
                  >
                    30秒休む
                  </button>
                </div>
              </section>

              {/* 3. 簡易グラフ / 今日のトレンド (24時間バーチャート) */}
              <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[11px] font-bold text-[#38BDF8]">
                    利用タイムライン
                  </span>
                  <span className="font-mono text-[10px] text-[#8B949E]">
                    ピーク: 20時台 (39分)
                  </span>
                </div>

                <div className="h-28 pt-3 pb-1 px-1 bg-[#0D1117] border border-[#21262D] rounded-lg flex items-end justify-between gap-[3px]">
                  {HOURLY_TREND_SECONDS.map((sec, hour) => {
                    const heightPct = Math.min(100, Math.round((sec / 2400) * 100));
                    const barColor =
                      sec >= 1800
                        ? "bg-[#F43F5E]"
                        : sec >= 900
                        ? "bg-[#F59E0B]"
                        : "bg-[#38BDF8]";
                    return (
                      <div key={hour} className="flex-1 flex flex-col items-center h-full justify-end">
                        <div
                          className={`w-full rounded-t ${barColor} transition-all`}
                          style={{ height: `${Math.max(heightPct, sec > 0 ? 6 : 2)}%` }}
                          title={`${hour}:00 - ${formatDurationClean(sec)}`}
                        />
                      </div>
                    );
                  })}
                </div>

                <div className="flex justify-between font-mono text-[9.5px] text-[#8B949E] px-1">
                  <span>00:00</span>
                  <span>06:00</span>
                  <span>12:00</span>
                  <span>18:00</span>
                  <span>23:59</span>
                </div>
              </section>

              {/* 4. 利用時間トップアプリ（上位5件・タップでアクションシート展開） */}
              <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[11px] font-bold text-[#00E676]">
                    利用時間ランキング
                  </span>
                  <span className="text-[10px] text-[#8B949E]">行タップで制限・設定</span>
                </div>

                <div className="space-y-2.5">
                  {INITIAL_TOP_APPS.map((app, idx) => {
                    const pct = Math.round((app.totalSeconds / totalUsageSeconds) * 100);
                    return (
                      <button
                        key={app.id}
                        onClick={() =>
                          setSelectedApp({
                            name: app.name,
                            packageName: app.id,
                            isGhost: app.isGhost,
                          })
                        }
                        className="w-full text-left bg-[#21262D]/50 hover:bg-[#21262D] border border-transparent hover:border-[#30363D] rounded-lg p-2.5 transition"
                      >
                        <div className="flex items-center justify-between text-xs mb-1.5">
                          <div className="flex items-center gap-2 min-w-0">
                            <span className="font-mono text-[11px] text-[#8B949E] w-5">
                              #{idx + 1}
                            </span>
                            <span
                              className="w-2.5 h-2.5 rounded-sm shrink-0"
                              style={{ backgroundColor: app.color }}
                            />
                            <span className="font-medium text-[#E6EDF3] truncate">
                              {app.name}
                            </span>
                            {app.isGhost && (
                              <span className="font-mono text-[9px] px-1.5 py-0.5 rounded bg-[#38BDF8]/15 text-[#38BDF8]">
                                透過
                              </span>
                            )}
                          </div>
                          <div className="font-mono text-[11px] text-[#E6EDF3] shrink-0">
                            {formatDurationClean(app.totalSeconds)}{" "}
                            <span className="text-[#8B949E]">({pct}%)</span>
                          </div>
                        </div>

                        <div className="w-full h-1.5 bg-[#0D1117] rounded-full overflow-hidden">
                          <div
                            className="h-full rounded-full"
                            style={{ width: `${pct}%`, backgroundColor: app.color }}
                          />
                        </div>
                      </button>
                    );
                  })}
                </div>
              </section>
            </>
          )}

          {activeTab === "logs" && (
            /* 【詳細ログタブ】: 生ログ垂れ流し防止・行単位ボタン廃止・時間表記一本化 */
            <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-[11px] font-bold text-[#38BDF8]">
                  :: ACTIVITY_LOGS // 切り替え履歴
                </span>
                <span className="text-[10px] text-[#8B949E]">行タップで操作メニュー</span>
              </div>

              {/* 5秒未満の極小ログをアコーディオンに集約 */}
              {microLogs.length > 0 && (
                <div className="bg-[#21262D]/70 border border-[#30363D] rounded-lg overflow-hidden">
                  <button
                    onClick={() => setMicroLogsOpen(!microLogsOpen)}
                    className="w-full px-3 py-2 text-left font-mono text-[11px] text-[#F59E0B] flex items-center justify-between"
                  >
                    <span>
                      {microLogsOpen ? "▾" : "▸"} その他の短時間切り替え（計{microLogs.length}回・
                      {formatDurationClean(microTotalSeconds)}）
                    </span>
                    <span className="text-[10px] text-[#8B949E]">5秒未満</span>
                  </button>

                  {microLogsOpen && (
                    <div className="border-t border-[#30363D] divide-y divide-[#30363D]/50 px-3 py-1">
                      {microLogs.map((log) => (
                        <button
                          key={log.id}
                          onClick={() =>
                            setSelectedApp({
                              name: log.appName,
                              packageName: log.packageName,
                              isGhost: log.isGhost,
                            })
                          }
                          className="w-full py-1.5 flex items-center justify-between text-left text-[11px] text-[#8B949E] hover:text-[#E6EDF3]"
                        >
                          <div className="flex items-center gap-2.5">
                            <span className="font-mono">{log.time}</span>
                            <span>{log.appName}</span>
                          </div>
                          <span className="font-mono">{formatDurationClean(log.durationSeconds)} ›</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* 5秒以上のメイン利用ログ（行ごとの[透過][除外]ボタンを撤去し、行タップに統一） */}
              <div className="divide-y divide-[#21262D]">
                {mainLogs.map((log) => (
                  <button
                    key={log.id}
                    onClick={() =>
                      setSelectedApp({
                        name: log.appName,
                        packageName: log.packageName,
                        isGhost: log.isGhost,
                      })
                    }
                    className="w-full py-2.5 px-1.5 flex items-center justify-between hover:bg-[#21262D]/60 rounded transition text-left"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <span className="font-mono text-[11px] text-[#38BDF8] shrink-0">
                        {log.time}
                      </span>
                      <span className="text-xs text-[#E6EDF3] truncate">{log.appName}</span>
                      {log.isGhost && (
                        <span className="font-mono text-[9px] px-1.5 py-0.5 rounded bg-[#38BDF8]/15 text-[#38BDF8]">
                          透過
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <span
                        className={`font-mono text-xs ${
                          log.durationSeconds >= 900
                            ? "text-[#F43F5E]"
                            : log.durationSeconds >= 300
                            ? "text-[#F59E0B]"
                            : "text-[#00E676]"
                        }`}
                      >
                        {formatDurationClean(log.durationSeconds)}
                      </span>
                      <span className="text-[#8B949E] text-sm">›</span>
                    </div>
                  </button>
                ))}
              </div>
            </section>
          )}

          {activeTab === "settings" && (
            /* 【ブロック / 制限設定タブ】 */
            <div className="space-y-3.5">
              <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div>
                    <div className="font-mono text-[11px] font-bold text-[#38BDF8]">
                      :: DEFENSE_DAEMON // 常駐監視サービス
                    </div>
                    <p className="text-[11px] text-[#8B949E] mt-0.5">
                      毎正時に予算リセット・5分ごとにCuraが通知
                    </p>
                  </div>
                  <input
                    type="checkbox"
                    checked={daemonActive}
                    onChange={(e) => setDaemonActive(e.target.checked)}
                    className="w-5 h-5 accent-[#00E676]"
                  />
                </div>

                <button
                  onClick={startThirtySecondBlock}
                  className="w-full py-2.5 bg-[#F43F5E] hover:bg-[#F43F5E]/90 text-white font-mono text-xs font-bold rounded-lg transition"
                >
                  今すぐ30秒間フルスクリーンで画面をブロック
                </button>
              </section>

              <section className="bg-[#161B22] border border-[#30363D] rounded-xl p-4 space-y-3">
                <div className="font-mono text-[11px] font-bold text-[#00E676]">
                  :: GAME_GHOST_MODE // ゲーム中透過・当たり判定無効化
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-[#E6EDF3]">
                    ゲームカテゴリのアプリを自動で半透明・タッチ透過にする
                  </span>
                  <input
                    type="checkbox"
                    checked={autoGameGhost}
                    onChange={(e) => setAutoGameGhost(e.target.checked)}
                    className="w-5 h-5 accent-[#38BDF8]"
                  />
                </div>
              </section>
            </div>
          )}
        </main>

        {/* ボトムナビゲーション（3タブ構成） */}
        <nav className="bg-[#161B22] border-t border-[#30363D] px-2 py-2 grid grid-cols-3 gap-1.5">
          {(
            [
              { id: "home", label: "ホーム / 監視" },
              { id: "logs", label: "詳細ログ" },
              { id: "settings", label: "ブロック / 設定" },
            ] as const
          ).map((tab) => {
            const isSelected = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`py-2 rounded-lg font-mono text-[11px] font-bold transition ${
                  isSelected
                    ? "bg-[#21262D] text-[#38BDF8] border border-[#30363D]"
                    : "text-[#8B949E] hover:text-[#E6EDF3]"
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </nav>

        {/* 行タップ時のボトムシート / モーダル（逃避・透過・監視除外・制限設定） */}
        {selectedApp && (
          <div
            className="absolute inset-0 bg-black/70 flex items-end justify-center z-20"
            onClick={() => setSelectedApp(null)}
          >
            <div
              className="w-full bg-[#161B22] border-t border-[#30363D] rounded-t-2xl p-4 space-y-2.5"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center justify-between border-b border-[#30363D] pb-2.5">
                <div>
                  <div className="font-mono text-[10px] text-[#38BDF8]">
                    :: APP_ACTION_SHEET
                  </div>
                  <div className="text-sm font-bold text-[#E6EDF3]">{selectedApp.name}</div>
                </div>
                <button
                  onClick={() => setSelectedApp(null)}
                  className="text-xs text-[#8B949E] px-2 py-1"
                >
                  閉じる
                </button>
              </div>

              <button
                onClick={() => setSelectedApp(null)}
                className="w-full py-2.5 px-3 bg-[#21262D] hover:bg-[#30363D] rounded-lg text-left text-xs text-[#38BDF8] font-medium"
              >
                {selectedApp.isGhost
                  ? "ゲーム透過モード（半透明・当たり判定なし）を解除"
                  : "ゲーム透過モード（半透明・当たり判定なし）に設定"}
              </button>

              <button
                onClick={() => setSelectedApp(null)}
                className="w-full py-2.5 px-3 bg-[#21262D] hover:bg-[#30363D] rounded-lg text-left text-xs text-[#F59E0B] font-medium"
              >
                監視除外リストに追加（システム・ランチャー等）
              </button>

              <button
                onClick={startThirtySecondBlock}
                className="w-full py-2.5 px-3 bg-[#F43F5E]/20 border border-[#F43F5E]/40 rounded-lg text-left text-xs text-[#F43F5E] font-bold"
              >
                今すぐ30秒間フルスクリーンで画面をブロックして休む
              </button>
            </div>
          </div>
        )}

        {/* 30秒間フルスクリーン強制ブロックオーバーレイ */}
        {blockCountdown !== null && (
          <div className="absolute inset-0 bg-[#0D1117]/95 z-30 flex flex-col items-center justify-center p-6 text-center space-y-4">
            <span className="font-mono text-xs text-[#F43F5E] tracking-widest font-bold">
              :: CURA_INTERVENTION // SCREEN_LOCKED
            </span>
            <p className="text-sm text-[#E6EDF3]">
              スマホを置いて、目を閉じて深呼吸してくださいねっ！
            </p>
            <div className="font-mono text-5xl font-bold text-[#00E676]">
              {blockCountdown}s
            </div>
            <button
              onClick={() => setBlockCountdown(null)}
              className="mt-4 font-mono text-[11px] text-[#8B949E] underline"
            >
              緊急解除 (デモ用)
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
