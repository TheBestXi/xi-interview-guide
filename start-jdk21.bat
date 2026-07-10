@echo off
REM ============================================================
REM  start-jdk21.bat
REM  作用：在当前命令行窗口临时切换到 Java 21（不影响系统全局）
REM  用途：跑 interview-guide 后端（要求 Java 21，源项目用虚拟线程）
REM
REM  双击运行 → 打开一个已切到 JDK 21 的命令行窗口
REM  在该窗口里跑：gradlew bootRun
REM  关掉窗口，JAVA_HOME 恢复全局默认（你的 17）
REM ============================================================

REM ---- JDK 21 安装路径（解压版，改路径请改这里）----
set "JAVA_HOME=D:\jdk\jdk-21.0.11+10"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- 切到源项目目录 ----
cd /d "%~dp0"

REM ---- 打印确认 ----
echo ============================================
echo  Java 版本（当前窗口专用）
echo ============================================
java -version
echo.
echo JAVA_HOME = %JAVA_HOME%
echo 当前目录 = %CD%
echo.
echo 现在可以运行：
echo   gradlew bootRun        启动后端
echo   gradlew :app:bootRun   同上（显式模块名）
echo ============================================
echo.

REM ---- 保持窗口打开 ----
cmd /k
