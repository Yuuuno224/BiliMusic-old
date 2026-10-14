@echo off
REM ===========================================================================
REM  BiliMusic build entry point for Windows.
REM
REM  Why this file exists:
REM    Windows PowerShell refuses to run unsigned .ps1 scripts under the default
REM    RemoteSigned/AllSigned policy, and .ps1 files without a UTF-8 BOM are
REM    mis-decoded by Windows PowerShell 5.1. This tiny wrapper sidesteps both:
REM    it launches PowerShell with -ExecutionPolicy Bypass.
REM
REM  Usage (same arguments as build.ps1):
REM    build.cmd                 -> assembleDebug
REM    build.cmd release         -> assembleRelease
REM    build.cmd clean           -> clean
REM    build.cmd assembleDebug -GradleArgs "--stacktrace"
REM ===========================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
