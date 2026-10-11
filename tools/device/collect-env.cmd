@echo off
rem Collect the phone's device environment into tools/device/reports/ (double-click me).
rem Details: tools/device/README.md
setlocal
cd /d "%~dp0..\.."

where py >nul 2>nul || goto :trypython
py -3 "tools\device\collect_env.py" %*
goto :done

:trypython
where python >nul 2>nul || goto :nopython
python "tools\device\collect_env.py" %*
goto :done

:nopython
echo [ERROR] Python not found ("py" / "python"). Please install Python 3 first.
echo         https://www.python.org/downloads/

:done
echo.
pause
