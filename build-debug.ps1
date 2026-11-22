$env:JAVA_HOME='C:\Users\admin\Documents\dsh\.toolchain\jdk\jdk-17.0.21+9'
$env:ANDROID_HOME='C:\Users\admin\Documents\dsh\.toolchain\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME='C:\Users\admin\Documents\dsh\.toolchain\home\.android'
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
$env:GRADLE_USER_HOME='C:\Users\admin\Documents\dsh\.toolchain\gradle-home'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$log = 'C:\Users\admin\Documents\dsh\fntv-tv\build.log'
& 'C:\Users\admin\Documents\dsh\.toolchain\gradle\gradle-8.9\bin\gradle.bat' -p 'C:\Users\admin\Documents\dsh\fntv-tv' assembleDebug --no-daemon --no-watch-fs --console=plain --info "-Duser.home=C:\Users\admin\Documents\dsh\.toolchain\home" *> $log
Write-Output "EXIT=$LASTEXITCODE"
Get-Content $log -Tail 40