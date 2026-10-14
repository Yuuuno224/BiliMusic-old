<#
.SYNOPSIS
    BiliMusic 一键构建脚本（Windows PowerShell）。

.DESCRIPTION
    这个脚本只做三件事：找到 JDK、找到 Gradle、把任务交给它。
    之所以需要它，是因为命令行直接跑 gradlew 时必须先设置 JAVA_HOME，
    而每个人的 JDK 装在哪里都不一样。

    脚本按下面的顺序自动查找，找不到才报错：
      1. 已有的 JAVA_HOME 环境变量
      2. Android Studio 自带的 JBR（推荐，版本与 AGP 匹配）
      3. 常见的手工安装路径

.PARAMETER Task
    要执行的构建任务，可省略。取值：
      debug    构建 Debug APK（默认）
      release  构建 Release APK（未签名）
      clean    清理构建产物
      wrapper  重新生成 Gradle Wrapper
    也可以直接写任意 Gradle 任务名，例如 assembleDebug。

.PARAMETER GradleArgs
    额外传给 Gradle 的参数，例如 '--info' 或 '--stacktrace'。

.EXAMPLE
    .\build.cmd
    构建 Debug APK，产物在 app\build\outputs\apk\debug\。
    （推荐用 build.cmd：它内部带了 -ExecutionPolicy Bypass，
      避免默认执行策略拒绝运行未签名的 .ps1。）

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 assembleDebug -GradleArgs '--stacktrace'
    排查构建失败时用：任务名直接写 Gradle 任务，额外参数走 -GradleArgs。
    注意 Task 参数只把 debug / release / clean / wrapper 这四个词翻译成
    assembleDebug / assembleRelease / clean / wrapper，其它值会原样传给 Gradle。

.EXAMPLE
    .\build.cmd release
    构建 Release APK（未签名）。
#>
param(
    [Parameter(Position = 0)]
    [string]$Task = 'debug',

    [string]$GradleArgs = ''
)

$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

# ---------------------------------------------------------------------------
# 1. 找 JDK
# ---------------------------------------------------------------------------
function Resolve-JavaHome {
    # 1) 环境里已经有了就直接用
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        Write-Host "[build] 使用已有的 JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
        return $env:JAVA_HOME
    }

    # 2) 依次尝试常见安装位置
    $candidates = @(
        'D:\Android\Android Studio\jbr',
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Eclipse Adoptium\jdk-21*'
    )
    foreach ($candidate in $candidates) {
        # 允许通配符，方便匹配带版本号的目录
        $resolved = Get-Item -Path $candidate -ErrorAction SilentlyContinue |
                    Select-Object -First 1
        if ($resolved -and (Test-Path (Join-Path $resolved.FullName 'bin\java.exe'))) {
            Write-Host "[build] 自动找到 JDK: $($resolved.FullName)" -ForegroundColor Cyan
            return $resolved.FullName
        }
    }
    return $null
}

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    Write-Host '[build] 错误：找不到可用的 JDK。' -ForegroundColor Red
    Write-Host '        请安装 JDK 21，或把 JAVA_HOME 指向你已有的 JDK 后重试。' -ForegroundColor Red
    Write-Host '        Android Studio 用户可以直接用它的内置 JBR：' -ForegroundColor Red
    Write-Host '          $env:JAVA_HOME = "D:\Android\Android Studio\jbr"' -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

# ---------------------------------------------------------------------------
# 2. 找 Gradle
# ---------------------------------------------------------------------------
# 优先用项目自带的 gradlew（版本由 gradle\wrapper\gradle-wrapper.properties 固定，
# 不会因为机器上装了别的 Gradle 版本而行为不一致）。
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    Write-Host "[build] 错误：找不到 $gradleWrapper" -ForegroundColor Red
    Write-Host '        这个文件应该跟着仓库一起提交，请检查是否被误删。' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 3. 把任务名翻译成 Gradle 任务
# ---------------------------------------------------------------------------
$gradleTask = switch ($Task.ToLowerInvariant()) {
    'debug'   { 'assembleDebug' }
    'release' { 'assembleRelease' }
    'clean'   { 'clean' }
    'wrapper' { 'wrapper' }
    default   { $Task }
}

Write-Host "[build] 任务: $gradleTask" -ForegroundColor Cyan
if ($GradleArgs) {
    Write-Host "[build] 额外参数: $GradleArgs" -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
# 4. 执行
# ---------------------------------------------------------------------------
# 用 & 调用并显式取 $LASTEXITCODE：PowerShell 不会自动把原生命令的退出码传出去
$arguments = @($gradleTask)
if ($GradleArgs) {
    $arguments += $GradleArgs.Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
}

& $gradleWrapper @arguments
$exitCode = $LASTEXITCODE

if ($exitCode -ne 0) {
    Write-Host "[build] 失败（退出码 $exitCode）。加 -GradleArgs '--stacktrace' 可看到详细堆栈。" -ForegroundColor Red
} elseif ($gradleTask -like 'assemble*' -or $gradleTask -like 'bundle*') {
    Write-Host '[build] 成功。产物目录：app\build\outputs\apk\' -ForegroundColor Green
} else {
    Write-Host "[build] 成功。" -ForegroundColor Green
}
exit $exitCode
