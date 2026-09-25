@echo off
REM 本地全量验证：把集成测试也跑上（CI 默认排除它们）
REM 单独写成脚本是因为 PowerShell 里 -Dtest.excluded.groups= 的空值会被解析器吃掉
cd /d "%~dp0"
call mvn -o -B verify -Dtest.excluded.groups=
