# 農民曆函式庫：以反射載入節氣與宜忌資料表，保留完整類別
-keep class com.nlf.calendar.** { *; }
-dontwarn com.nlf.calendar.**

# 無障礙服務由系統以類別名稱綁定
-keep class tw.liuli.launcher.keys.KeyRemapService { *; }
