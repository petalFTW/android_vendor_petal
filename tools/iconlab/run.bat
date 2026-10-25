@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Create the virtual environment first:
  echo   py -3.12 -m venv .venv
  echo   .venv\Scripts\activate
  echo   pip install -r requirements.txt
  exit /b 1
)
call ".venv\Scripts\activate.bat"
python server.py --teacher existing %*
