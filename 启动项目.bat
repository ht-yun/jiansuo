@echo off
setlocal
chcp 65001 >nul
set "APP_JAR=%~dp0company-profile-query-0.1.0-SNAPSHOT.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0target\company-profile-query-0.1.0-SNAPSHOT.jar"
if not exist "%APP_JAR%" (
  echo 未找到 company-profile-query-0.1.0-SNAPSHOT.jar
  echo 请确认发布包文件完整，或先执行 Maven 构建。
  pause
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo 未找到 Java。请安装 Java 17 或更高版本后重试。
  pause
  exit /b 1
)
echo 正在启动企业信息自动采集项目...
echo 启动后请在 Edge 打开 http://127.0.0.1:8080/
echo 关闭此窗口将停止项目服务。
java -jar "%APP_JAR%"
pause
