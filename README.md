# Pocket 截圖助手（Android）

在螢幕上放一個浮動按鈕，讓你在 Pokémon TCG Pocket 卡片收藏頁一邊滑、一邊自動截圖。
只會「截圖」，不會操作或修改遊戲。

## 取得 APK（不用安裝任何開發工具）

1. 到 https://github.com 註冊帳號（免費）。
2. 右上角「+」→ New repository → 名稱隨意（例如 pocketshot）→ Create。
3. 在新的 repository 頁面點「uploading an existing file」，
   把這個資料夾**裡面的所有檔案與資料夾**（包含 `.github`）拖進去 → Commit changes。
   ※ `.github` 是隱藏資料夾，電腦上要開啟「顯示隱藏檔案」才看得到。
4. 點上方「Actions」分頁，會看到「Build APK」自動開始跑（約 3–5 分鐘）。
   沒有自動跑的話，點左側 Build APK → Run workflow。
5. 完成（綠色勾勾）後點進去，最下面 Artifacts 的「PocketShot-apk」下載，解壓縮得到 `app-debug.apk`。
6. 把 APK 傳到手機安裝（系統會要求允許「安裝不明來源應用程式」）。

也可以用 Android Studio 開啟此資料夾，直接 Run 到手機上。

## 使用方式

1. 打開「Pocket 截圖助手」，依序按 ①（允許顯示在其他應用程式上層）、②（允許通知）。
2. 按「開始」，系統詢問螢幕錄製時選 **整個螢幕**。
3. 切到遊戲 → 卡片收藏頁（建議用能看到卡號的顯示方式）。
4. 浮動列按鈕：
   - ⠿ 拖曳移動位置
   - 📷 立刻截一張
   - ▶ 自動 / ⏸ 暫停：每隔設定秒數截一張，畫面沒變就略過
   - ✕ 結束
5. 自動模式下，每次截圖後往下滑一頁、停一下等它拍，再滑下一頁。
6. 截圖存在相簿 `Pictures/PocketShots/日期時間/`。

## 注意
- 需要 Android 10 以上。
- 若截圖是全黑，代表遊戲禁止截圖，這個方法就無法使用。
- 浮動按鈕在截圖瞬間會閃一下，是為了不把按鈕拍進去。
