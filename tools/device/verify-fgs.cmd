@echo off
rem FGS real-device verification -- double-click this file.
rem Details: tools/device/README.md
setlocal
cd /d "%~dp0..\.."

where py >nul 2>nul || goto :trypython
py -3 "tools\device\verify_fgs.py" %*
goto :done

:trypython
where python >nul 2>nul || goto :nopython
python "tools\device\verify_fgs.py" %*
goto :done

:nopython
echo [ERROR] Python not found ("py" / "python"). Please install Python 3 first.
echo         https://www.python.org/downloads/

:done
echo.
pause
