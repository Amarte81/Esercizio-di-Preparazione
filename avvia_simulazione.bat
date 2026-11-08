@echo off
chcp 65001 > nul
echo ====================================================================
echo   AVVIO SIMULAZIONE SISTEMA DISTRIBUITO INQUINAMENTO + MONITOR
echo   (Configurazione: 1 Server, 1 Monitor, 2 Centraline - 4 Finestre)
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
echo 1) Avvio Server (Finestra 1)...
start "SERVER INQUINAMENTO" cmd /k "chcp 65001 > nul && title SERVER INQUINAMENTO && java ServerInquinamento"

timeout /t 2 > nul

echo.
echo 2) Avvio Monitor di Emergenza (Finestra 2 - Monitor Unico)...
start "MONITOR EMERGENZA" cmd /k "chcp 65001 > nul && title MONITOR EMERGENZA - SALA CONTROLLO && java ClientMonitor Monitor-Centrale"

timeout /t 1 > nul

echo.
echo 3) Avvio Client Sensore Zona-Centro (Finestra 3)...
start "SENSORE ZONA-CENTRO" cmd /k "chcp 65001 > nul && title SENSORE ZONA CENTRO && java ClientSensore Centro"

timeout /t 1 > nul

echo.
echo 4) Avvio Client Sensore Zona-Industriale (Finestra 4)...
start "SENSORE ZONA-INDUSTRIALE" cmd /k "chcp 65001 > nul && title SENSORE ZONA INDUSTRIALE && java ClientSensore Industriale"

echo.
echo Tutte e 4 le componenti sono state avviate in finestre separate!
echo Nota: Nel terminale del Server, inserisci la soglia (es. 88.0) e premi INVIO.
echo.
pause
