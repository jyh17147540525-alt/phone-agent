$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = "C:\Users\Mikel.Daniela\Doubao\chats\2026-09-23\new-chat-1\verify\a11ydump"
$bt = "D:\AndroidDev\sdk\build-tools\36.0.0"
$androidJar = "D:\AndroidDev\sdk\platforms\android-36\android.jar"
$jdk = "C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin"
Set-Location $root

Write-Host "=== 1/6 aapt2 compile ==="
& "$bt\aapt2.exe" compile --dir res -o res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 2/6 aapt2 link ==="
& "$bt\aapt2.exe" link -o base.apk -I $androidJar --manifest AndroidManifest.xml --min-sdk-version 33 --target-sdk-version 35 res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 3/6 javac ==="
$srcs = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\javac.exe" --release 17 -cp $androidJar -d classes $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== 4/6 d8 dex ==="
if (-not (Test-Path dexout)) { New-Item -ItemType Directory dexout | Out-Null }
$clss = Get-ChildItem classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
cmd /c "`"$bt\d8.bat`" --release --lib $androidJar --output dexout $clss 2>&1"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
Get-ChildItem dexout | Select-Object -ExpandProperty Name

Write-Host "=== 5/6 注入 dex + zipalign ==="
& "$jdk\jar.exe" uf base.apk -C dexout classes.dex
& "$bt\zipalign.exe" -f 4 base.apk aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 6/6 签名 ==="
$ks = "C:\Users\Mikel.Daniela\Doubao\chats\2026-09-23\new-chat-1\verify\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jdk\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey -storepass android -keypass android -dname "CN=Debug,O=Verify,C=CN" -keyalg RSA -validity 10000 2>&1 | Out-Null
}
cmd /c "`"$bt\apksigner.bat`" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out signed.apk aligned.apk 2>&1"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "=== 产物 ==="
Get-Item signed.apk | Select-Object Name, Length
Write-Host "BUILD_OK"
