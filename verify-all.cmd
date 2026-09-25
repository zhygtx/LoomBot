@echo off
REM 本地全量验证（离线 + 干净）。
REM
REM 为什么单独写成脚本 / 为什么必须 clean：
REM   1. 不 clean 时 target 里的旧产物会参与打包判断，容易出现「改了没生效」的误判。
REM      注意这个坑不会在 CI 上出现（CI 每次都是干净检出），只坑本地。
REM   2. -o 走本地仓库缓存，避免每次联网。
REM
REM 测试与覆盖率门槛已于 2026-09-25 全部移除（见 docs/decisions.md D59）。
REM 现在 verify 只做三件事：Spotless 格式校验 → 编译 → 打 fat jar。
cd /d "%~dp0"
call mvn -o -B clean verify
