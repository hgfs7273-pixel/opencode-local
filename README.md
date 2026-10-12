# Opencode 本地版 Android App（圖形介面）

手機自己跑 `opencode serve` 在 `127.0.0.1:4096`，
App 的圖形 UI（卡片、按鈕、進度條、對話氣泡）用 HTTP 跟本機後端講話。
**不連電腦、不開網頁、不打終端指令。**

> 重要觀念：程式碼執行在手機沙盒，但模型推論仍需網路＋你自己的
> API Key（OpenAI / Anthropic / Gemini，Key 只存本機）。
> 純離線跑程式碼模型目前手機還撐不起來。

## 架構

```
┌─ 圖形 UI（Kotlin + Compose，無終端）─┐   ┌─ 本機後端 ─────────────┐
│ 設定頁：模型卡片＋API Key＋一鍵啟動    │   │ filesDir/opencode-bin/ │
│ 主頁：任務卡片＋新任務按鈕             │ ↔ │ filesDir/workspace/    │
│ 對話頁：氣泡＋批准/拒絕按鈕＋快捷動作  │   │ 127.0.0.1:4096 serve   │
└──────────────────────────────────────┘   └────────────────────────┘
```

- 二進位：`guysoft/opencode-termux` 的 `opencode-android-aarch64.zip`
  （Bionic 原生編譯，免 glibc，最適合包進 App）。
- 首次啟動才下載引擎（約 60MB），APK 本體保持輕量。
- 前景服務保活（Android 12+ 會殺背景進程，被殺後 UI 有一鍵重啟）。

## 不用裝任何軟體也能封裝成 APK（雲端編譯）

1. 在 GitHub 建一個新的空倉庫（例如 `opencode-local`），不要勾 README。
2. 把 `D:\AI\新增資料夾` 的內容推上去（見下），或直接用網頁上傳整個資料夾。
3. 到倉庫的 **Actions → Build APK → Run workflow**（push 後也會自動跑）。
4. 跑完到該次執行頁下載 `opencode-local-debug-apk` → 傳到手機安裝。
5. 手機需 arm64（近 5 年手機幾乎都是），允許「安裝未知應用」。

### 方式 A：指令推送（電腦有 git 即可）

```bash
cd "D:\AI\新增資料夾"
git add -A
git commit -m "opencode local android v0.1"
git branch -M main
git remote add origin https://github.com/<你的帳號>/<倉庫名>.git
git push -u origin main
```

### 方式 B：網頁上傳（連 git 都不用）

GitHub 倉庫頁 → Add file → Upload files → 把本資料夾所有檔案拖進去 → Commit。

## 手機首次使用

1. 開 App → 選模型商 → 貼 API Key → 按「一鍵安裝並啟動」。
2. 等進度條跑完 → 自動進主頁 → 按「新任務」開始。
3. AI 要改檔時會跳批准卡，按批准/拒絕即可，不用打指令。

## 專案結構

```
.github/workflows/build-apk.yml   雲端編譯流程
opencode-local/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/opencodelocal/app/
    MainActivity.kt                設定/主頁/對話三個圖形頁
    backend/OpencodeConfig.kt      連線設定＋二進位下載位址
    backend/OpencodeBackend.kt     啟動 serve＋健康檢查
    backend/OpencodeInstaller.kt   首啟下載＋解壓（進度條）
    backend/OpencodeApi.kt         會話/訊息/批准 API
    backend/OpencodeService.kt     前景服務保活
```

## 現況與限制（誠實告知）

- 此專案骨架已可 push 即編；本機因無 Android SDK，尚未在本機編譯驗證，
  以 GitHub Actions 的雲端編譯結果為準。若 Actions 紅燈，把 log 貼回來即可修。
- 只支援 arm64 手機；x86 模擬器、32 位元舊機不支援。
- 長任務可能被 Android 幻影進程殺掉（所有本機 agent 方案的通病），
  App 內有重啟按鈕，重要任務建議分段做。
