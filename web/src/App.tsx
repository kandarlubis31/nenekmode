import { useCallback, useEffect, useState } from "react";
import { prefs } from "./lib/prefs";
import { rescheduleAllOnStart } from "./lib/scheduling";
import { pullAndMergeNative } from "./native/sync";
import * as bridge from "./native/bridge";
import MainScreen from "./screens/MainScreen";
import WizardScreen from "./screens/WizardScreen";
import SettingsScreen from "./screens/SettingsScreen";
import BoardScreen from "./screens/BoardScreen";

type Screen = "wizard" | "main" | "settings" | "board";

function applyAccessibility() {
  const html = document.documentElement;
  html.classList.toggle("hc", prefs.getBoolean("high_contrast", false));
  html.classList.toggle("font-large", prefs.getInt("font_size", 1) === 2);
  html.classList.toggle("font-xlarge", prefs.getInt("font_size", 1) === 3);
}

export default function App() {
  const [screen, setScreen] = useState<Screen>(() => {
    if (prefs.getBoolean("board_mode", false)) return "board";
    if (prefs.getBoolean("is_first_time", true)) return "wizard";
    return "main";
  });
  const [tick, setTick] = useState(0); // dipakai buat refresh setelah kembali dari child

  const refresh = useCallback(() => {
    applyAccessibility();
    setTick((t) => t + 1);
  }, []);

  useEffect(() => {
    applyAccessibility();
    // pull state runtime dari mesin native (Mode Teman/alarm) lalu reschedule
    void pullAndMergeNative().finally(() => rescheduleAllOnStart());
    // kembali ke foreground -> sinkron lagi (mis. Mode Teman menulis state)
    const onVisible = () => {
      if (document.visibilityState === "visible") void pullAndMergeNative();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, []);

  useEffect(() => {
    // izin notifikasi: native via plugin, browser via Notification API
    if (bridge.hasNativeBridge()) {
      void bridge.requestNotificationsPermission();
    } else {
      try {
        if ("Notification" in window && Notification.permission === "default") {
          void Notification.requestPermission();
        }
      } catch {
        /* abaikan */
      }
    }
  }, []);

  void tick;

  if (screen === "wizard") {
    return (
      <WizardScreen
        onFinish={() => {
          refresh();
          setScreen("main");
        }}
      />
    );
  }

  if (screen === "settings") {
    return <SettingsScreen onBack={() => { refresh(); setScreen("main"); }} />;
  }

  if (screen === "board") {
    return <BoardScreen onExit={() => { refresh(); setScreen("main"); }} />;
  }

  return (
    <MainScreen
      key={tick}
      onOpenSettings={() => setScreen("settings")}
      onOpenBoard={() => setScreen("board")}
    />
  );
}
