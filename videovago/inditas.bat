@echo off
rem Videovago inditasa. Videot is ra lehet huzni erre a fajlra.
cd /d "%~dp0"
where py >nul 2>nul && (py -3 videovago.py %* & goto :end)
where python >nul 2>nul && (python videovago.py %* & goto :end)
echo Nem talalhato Python. Telepitsd: https://www.python.org/downloads/
pause
:end
