$adb = 'C:\Users\colorful\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$xmlout = (& $adb -s emulator-5554 shell "uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml" 2>&1 | Out-String)
$nodes = $xmlout -split "><" | ForEach-Object { $_ -replace "^<|>$", "" }
Write-Host "=== 关键元素 ==="
$nodes | Where-Object { $_.Contains('tv_mode_title') -or $_.Contains('tv_mode_subtitle') -or $_.Contains('rv_bookstore') -or $_.Contains('rv_source_list') -or $_.Contains('dialog_source_select') -or $_.Contains('tv_book_name') -or $_.Contains('hsv_sub_category') } | Select-Object -First 30
Write-Host "=== Activity ==="
& $adb -s emulator-5554 shell dumpsys activity top 2>&1 | Select-String -Pattern "ACTIVITY com.example" | Select-Object -First 2
$snap = "d:\android\reading-app\screenshots\18_debug.png"
& $adb -s emulator-5554 exec-out screencap -p > $snap
Write-Host "截图: $snap"
