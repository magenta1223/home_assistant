@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0logs" mkdir "%~dp0logs"
set "OLLAMA_VULKAN=false"
set "PATH=%APPDATA%\npm;%PATH%"
call "%~dp0app\build\install\app\bin\app.bat" >> "%~dp0logs\server.log" 2>&1
exit /b %ERRORLEVEL%
