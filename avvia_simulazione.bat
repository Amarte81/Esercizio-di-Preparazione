@echo off
chcp 65001 > nul
echo ====================================================================
echo   AVVIO SIMULAZIONE SISTEMA DISTRIBUITO INQUINAMENTO + MONITOR
echo ====================================================================

cd /d "%~dp0"

echo Compilazione sorgenti Java...
javac -encoding UTF-8 *.java
if %ERRORLEVEL% NEQ 0 (
    echo Errore di compilazione!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo 1) Avvio Server (apertura nuova finestra)...
start "SERVER INQUINAMENTO" cmd /k "chcp 65001 > nul && title SERVER INQUINAMENTO && java ServerInquinamento"

timeout /t 2 > nul

echo.
echo 2) Avvio Monitor di Emergenza 1 (apertura nuova finestra)...
start "MONITOR EMERGENZA 1" cmd /k "chcp 65001 > nul && title MONITOR 1 - SALA CONTROLLO && java ClientMonitor Monitor-SalaControllo"

timeout /t 1 > nul

echo.
echo 3) Avvio Monitor di Emergenza 2 (apertura nuova finestra)...
start "MONITOR EMERGENZA 2" cmd /k "chcp 65001 > nul && title MONITOR 2 - PROTEZIONE CIVILE && java ClientMonitor Monitor-ProtezioneCivile"

timeout /t 1 > nul

echo.
echo 4) Avvio Client Sensore Zona-Centro...
start "SENSORE ZONA-CENTRO" cmd /k "chcp 65001 > nul && title SENSORE ZONA CENTRO && java ClientSensore Centro"

timeout /t 1 > nul

echo.
echo 5) Avvio Client Sensore Zona-Industriale...
start "SENSORE ZONA-INDUSTRIALE" cmd /k "chcp 65001 > nul && title SENSORE ZONA INDUSTRIALE && java ClientSensore Industriale"

echo.
echo Tutte le componenti sono state avviate in finestre separate!
echo Nota: Nel terminale del Server, inserisci la soglia (es. 88.0) e premi INVIO.
echo.
pause
