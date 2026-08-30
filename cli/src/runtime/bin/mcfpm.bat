@echo off
setlocal
set "APP_HOME=%~dp0.."
"%APP_HOME%\runtime\bin\java.exe" -classpath "%APP_HOME%\lib\*" moe.afox.mcfpm.cli.MainKt %*
exit /b %ERRORLEVEL%
