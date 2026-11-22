# 一键推送 / 发版（PowerShell）
#
# 用法（不要把 token 写进文件或提交到仓库）：
#   $env:GH_TOKEN = "github_pat_xxx"     # fine-grained PAT：Contents 读写 + Workflows 读写
#   powershell -NoProfile -File tools\push.ps1
#
# 只推代码：
#   powershell -NoProfile -File tools\push.ps1 -Message "feat: xxx"
#
# 发版（自动打 tag，触发 GitHub Actions 构建并建 Release）：
#   powershell -NoProfile -File tools\push.ps1 -Tag v1.1.0
#
# token 只存在于本次进程的命令行参数里，不会写入 git remote / .git/config。

param(
    [string]$Token = $env:GH_TOKEN,
    [string]$Message = "",
    [string]$Tag = ""
)

$ErrorActionPreference = 'Stop'
$repo = 'https://github.com/lrf1995/FnTvbox.git'

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 token：请先 `$env:GH_TOKEN = `"github_pat_xxx`" 再运行（需 Contents + Workflows 读写权限）"
}

Push-Location (Join-Path $PSScriptRoot '..')
try {
    # git 的 schannel 在部分 Windows 环境会报 SEC_E_NO_CREDENTIALS，改用 openssl 后端
    $gitArgs = @('-c', 'http.sslBackend=openssl')

    if ($Message.Trim().Length -gt 0) {
        git add -A
        git commit -m $Message
    }

    if ($Tag.Trim().Length -gt 0) {
        $version = (Select-String -Path 'app/build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
        Write-Host "当前 versionName=$version，打 tag $Tag"
        git tag $Tag -m "FnTV $Tag"
    }

    $authed = "https://x-access-token:$Token@github.com/lrf1995/FnTvbox.git"
    git @gitArgs push $authed "HEAD:main"
    if ($Tag.Trim().Length -gt 0) {
        git @gitArgs push $authed $Tag
    }
    Write-Host "推送完成：$repo  （发版请到 Actions 查看构建进度）"
}
finally {
    Pop-Location
}