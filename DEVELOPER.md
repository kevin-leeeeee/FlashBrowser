# 📱 FlashBrowser Android 開發者說明文件

本專案是一個專為 Android 平台開發的原生 WebView 瀏覽器殼，針對 Flash 模擬器（Ruffle）進行了深度優化與極早期的底層網路通訊攔截。能讓使用者在現代 Android 手機上流暢執行 Flash 網頁遊戲（如《保衛羊村》），並提供完整的網路封包監控與自動修正機制。

---

## 🚀 主要功能與技術特色

### 1. 🦘 Ruffle 執行環境動態注入
在 WebView 載入網頁完成時（`onPageFinished`），主動評估並注入 Ruffle 引擎的 JS 載入代碼，藉此在不支援 Flash 插件的現代 Android 系統上重現 Flash SWF 的執行環境。
* **字型最佳化**：預設設定中文字型源（`LXGW WenKai Lite`），防範 Flash 中文字型因系統缺失而顯示為空白或亂碼。
* **執行設定**：設定自動播放（`autoplay: "on"`）與隱藏解鎖覆蓋層（`unmuteOverlay: "hidden"`）。

### 2. 🔌 極早期網路通訊攔截 (Network Hook)
為了能讓遊戲與後端伺服器在 WebView 的安全沙箱環境下正常通訊，專案在資源載入階段（`onLoadResource`）便會提前注入網路代理攔截指令：
* **WebSocket 代理**：攔截 WebSocket 發送與接收的二進位資料封包，轉換為 ASCII / Hex 格式，並以 `[FLASH_DEBUG]` 前綴輸出至 Logcat。
* **XHR / Fetch 攔截與 AMF 修改**：
  * 對傳輸的二進位 `ArrayBuffer` 與 `Blob` 進行深度攔截。
  * **AMF0/AMF3 格式修復**：自動偵測通訊中不相容的現代結構（如網頁版與伺服器間的 ECMAArray），並將 `wids`、`slave_ids` 等特定欄位自動修改並重構成傳統 Flash 伺服器支援 of `Strict Array (0x0A)`，避免伺服器端發生反序列化失敗而斷線。

### 3. 🎯 雙指縮放與設定保存 (Zoom Control)
* 內建支援網頁原生雙指縮放。
* 提供「放大 (Zoom In)」與「縮小 (Zoom Out)」控制按鈕，手動強制鎖定並調整網頁縮放比例。
* 縮放比例會儲存至 `SharedPreferences`（儲存庫名稱為 `BrowserSettings`），使使用者下次開啟 App 時能自動套用上一次的最佳比例。

### 4. 🖐️ 手勢偵測與導覽列自動隱藏
* WebView 設有觸控手勢監聽器。
* 當手指**向下劃動**時，會自動拉出頂部導覽列（AppBar），顯示 URL 輸入欄與控制按鈕。
* 當手指**向上劃動**時，則自動收起導覽列，讓 Flash 遊戲能以最大化視角執行，防止畫面遮擋。

---

## 📂 關鍵檔案說明

* **[MainActivity.kt](file:///c:/Users/HP_600_G1_2ND/Desktop/FlashBrowser/app/src/main/java/com/example/flashbrowser/MainActivity.kt)**: 
  * 控制 WebView 的初始化設定（啟用 JS、DOM 儲存、混合內容加載等）。
  * 實作手勢隱藏 AppBar 邏輯與雙指縮放數據保存。
  * 實作 `injectRuffle()` 與底層 WebSocket / HTTP AMF 修復腳本的注入。
  * 預設首頁 `homeUrl` 定義於此。
* **[activity_main.xml](file:///c:/Users/HP_600_G1_2ND/Desktop/FlashBrowser/app/src/main/res/layout/activity_main.xml)**: UI 佈局檔，包含 `AppBarLayout`、`Toolbar`、瀏覽與縮放控制按鈕，以及 `WebView` 容器。
* **[AndroidManifest.xml](file:///c:/Users/HP_600_G1_2ND/Desktop/FlashBrowser/app/src/main/AndroidManifest.xml)**: 聲明網際網路權限 (`android.permission.INTERNET`)，並開啟 `usesCleartextTraffic="true"` 以相容未加密的 HTTP 遊戲通訊與資源載入。

---

## 🛠️ 開發與設定調整

### 1. 修改預設首頁
若需要變更開啟 App 時預設載入的網站，請至 [MainActivity.kt:L27](file:///c:/Users/HP_600_G1_2ND/Desktop/FlashBrowser/app/src/main/java/com/example/flashbrowser/MainActivity.kt#L27) 修改 `homeUrl` 常數：
```kotlin
private val homeUrl = "https://your-custom-flash-site.com/"
```

### 2. 查看除錯 Logcat 日誌
在 Android Studio 中，您可以過濾標籤 `FLASH_DEBUG` 或 `WebViewConsole` 來即時查看經攔截的網路請求、發送的 AMF 十六進位數據與錯誤日誌：
```bash
# 在 Logcat 中過濾
tag:FLASH_DEBUG
```

### 3. 建置要求
* **Android SDK**: Min SDK 21 (Lollipop), Target SDK 33
* **Build System**: Gradle 8.x + Kotlin
