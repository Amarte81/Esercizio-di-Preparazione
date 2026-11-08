# Sistema Distribuito di Monitoraggio Ambientale e Notifica Allarmi (TPSIT)

Applicazione distribuita concorrente di tipo **Client-Server** sviluppata in **Java SE** (senza attributi `static`), corredata da una presentazione interattiva in formato **16:9 con simulatore animato a stati** ottimizzata per lavagne LIM e schermi widescreen.

---

## 🎯 Obiettivi Didattici e Contesto
In un'infrastruttura di rilevamento urbano, molteplici centraline periferiche (**Sensori**) trasmettono campionamenti della concentrazione di inquinante atmosferico a un **Server Centrale**. Quest'ultimo aggrega le misurazioni calcolando la **media cumulativa globale** in tempo reale mediante **mutua esclusione con Semaforo**.

Quando la media supera la soglia prefissata all'avvio, il Server propaga tempestivamente la notifica di emergenza a tutte le postazioni di supervisione collegate (**Monitor di Controllo / Emergenza**).

---

## 🏗️ Architettura del Sistema

```
+-----------------------------------------------------------------------------------+
|                                 SERVER CENTRALE                                   |
|                                                                                   |
|  [ServerSocket : 8080] <--- accept()                                              |
|            |                                                                      |
|            +---> Thread GestoreClient (Sensore)                                  |
|            |           |                                                          |
|            |      [Semaphore(1, true)] <--- Mutua Esclusione Sezione Critica      |
|            |           |                                                          |
|            |      [RegistroInquinamento]                                          |
|            |        - N campioni                                                  |
|            |        - Somma cumulativa                                            |
|            |        - Media ponderata                                             |
|            |        - Soglia massima                                              |
|            |        - List<PrintWriter> monitorConnessi (CopyOnWriteArrayList)    |
|            |                                                                      |
|            +---> Thread GestoreClient (Monitor)                                  |
|                        |                                                          |
|                        +---> Registrazione Dinamica & Connessione Persistente     |
+-----------------------------------------------------------------------------------+
          ▲                                                   ▲
          | (Short-lived TCP)                                 | (Long-lived TCP)
          |                                                   |
+-------------------+                               +-------------------+
|  CLIENT SENSORE   |                               |  CLIENT MONITOR   |
|  - Genera 80..100 |                               |  - Handshake      |
|  - Invia dato     |                               |  - Ascolto push   |
|  - Chiude socket  |                               |  - Allerta sonora |
|  - Sleep 5s       |                               |    e visiva       |
+-------------------+                               +-------------------+
```

---

## 🔍 Focus Tecnico: Il Processo di Registrazione Dinamica dei Monitor

La registrazione dinamica dei Monitor rappresenta uno dei nuclei architetturali più rilevanti dell'applicazione distribuita. Permette il supporto al **collegamento a caldo (*hot-plugging*)** di nuove postazioni di visualizzazione senza riavviare il server e senza interrompere le attività dei sensori.

### 1. Perché è "Dinamica"?
- Il Server **non conosce a priori** quanti Monitor si collegheranno, quali saranno i loro indirizzi IP o se si scollegheranno temporaneamente.
- I Monitor possono entrare o uscire dalla rete in qualsiasi istante: la lista dei destinatari dell'allarme cresce e si riduce a runtime in modo trasparente.

---

### 2. Ciclo di Vita della Registrazione Dinamica (Fase per Fase)

```
MONITOR CLIENT                             SERVER THREAD (GestoreClient)           REGISTRO CONDIVISO
      |                                                 |                                   |
      |======= 1. Connessione TCP (Porta 8080) ========>|                                   |
      |------- 2. Handshake: "MONITOR\n" -------------->|                                   |
      |                                                 |-- 3. registro.aggiungiMonitor() ->| (Aggiunto a CopyOnWriteArrayList)
      |<------ 4. "OK: Registrazione completata\n" -----|                                   |
      |                                                 |                                   |
      | (In ascolto passivo su readLine())              | (Thread bloccato su in.readLine())|
      |                                                 |                                   |
      |                                                 |                                   | <<< Sensore aggiorna media > soglia >>>
      |<====== 5. Broadcast Allarme Push ===================================================| (inviaNotificaAllarme)
      |                                                 |                                   |
      | (Chiusura finestra / Ctrl+C / Caduta rete)      |                                   |
      |XXXXXXX 6. Disconnessione Socket XXXXXXXXXXXXXXX>| (in.readLine() restituisce null)  |
      |                                                 |-- 7. registro.rimuoviMonitor() -->| (Rimosso in modo sicuro)
      |                                                 |-- 8. Chiusura socket & Thread ----|
```

#### Fase 1: Handshake e Riconoscimento del Ruolo
Quando un client si connette al Server sulla porta 8080, la `ServerSocket.accept()` istanzia un thread `GestoreClient`.  
Il thread legge il primo messaggio sulla socket:
- Se la riga inizia con `SENSOR:<valore>`, il thread riconosce un sensore effimero.
- Se la riga è `MONITOR`, il thread instrada la connessione verso il metodo dedicato `gestisciMonitor(in, out, clientInfo)`.

#### Fase 2: Iscrizione Thread-Safe con `CopyOnWriteArrayList`
Lo stream di scrittura verso il monitor (`PrintWriter out`) viene registrato nella struttura dati dello stato condiviso:
```java
registro.aggiungiMonitor(out);
```
**Perché `CopyOnWriteArrayList<PrintWriter>` e non una normale `ArrayList`?**
- In un sistema concorrente, mentre i sensori generano allarmi e scorrono la lista dei monitor per inviare notifiche, nuovi monitor potrebbero registrarsi o disconnettersi.
- Una normale `ArrayList` genererebbe un'eccezione fatale `java.util.ConcurrentModificationException`.
- `CopyOnWriteArrayList` risolve elegantemente il problema: ogni operazione di scrittura (aggiunta/rimozione) crea una copia immutabile dell'array, mentre le letture (invio allarmi in broadcast) iterano su uno snapshot sicuro e lock-free, senza rallentare i thread di calcolo.

#### Fase 3: Handshake di Conferma e Invio Stato Iniziale
Il server invia al monitor un messaggio di benvenuto contenente la soglia d'allarme correntemente impostata:
```java
out.println(String.format("OK: Registrazione monitor completata. Soglia configurata: %.2f. In attesa di allerte...",
        registro.getSogliaMassima()));
```
Il monitor conferma la ricezione e commuta il proprio stato interno su **ASCOLTO PASSIVO**.

#### Fase 4: Mantenimento della Connessione Persistente (Long-Lived Socket)
A differenza del sensore che chiude la connessione immediatamente dopo aver ricevuto l'ACK, il thread del monitor entra in un loop di attesa passiva:
```java
String linea;
while ((linea = in.readLine()) != null) {
    if (linea.equalsIgnoreCase("PING")) {
        out.println("PONG"); // Supporto opzionale per heartbeat
    }
}
```
Finché il socket rimane integro, `in.readLine()` rimane bloccato senza consumare cicli di CPU (stato `WAITING` gestito dal kernel del sistema operativo).

#### Fase 5: Notifica Asincrona Disaccoppiata (Fuori dalla Sezione Critica)
Quando un sensore registra una rilevazione che porta $\text{Media} > \text{Soglia}$, il server invoca:
```java
if (allarmeAttivo) {
    inviaNotificaAllarme(esito);
}
```
**Dettaglio architetturale critico**: l'iterazione di invio sui monitor avviene **dopo** aver rilasciato il semaforo con `semaforo.release()`. Se l'invio avvenisse all'interno della sezione critica, un monitor con connessione lenta (*slow consumer*) terrebbe occupato il semaforo, bloccando tutti gli altri sensori periferici!

#### Fase 6: Deregistrazione Automatica Robusta (Tolleranza ai Guasti)
Se un monitor viene chiuso bruscamente (es. terminazione del processo, caduta della linea):
1. Il metodo `in.readLine()` restituisce immediatamente `null` oppure solleva una `SocketException`.
2. Il blocco `finally` in `GestoreClient` garantisce la deregistrazione immediata:
   ```java
   finally {
       registro.rimuoviMonitor(out);
       log("MONITOR rimosso. Monitor rimanenti: " + registro.getNumeroMonitorConnessi());
   }
   ```
3. Se un guasto si verifica proprio durante l'invio di un allarme, il blocco `try-catch` all'interno di `inviaNotificaAllarme` intercetta l'errore di scrittura e provvede all'eliminazione preventiva del canale non più raggiungibile, prevenendo memory leak e socket orfane.

---

## 📁 Struttura dei File del Progetto (Architettura Compatta a 3 Classi)

| File | Ruolo / Descrizione |
| :--- | :--- |
| **`ServerInquinamento.java`** | **Classe 1 (Server Centrale):** gestisce `ServerSocket`, contatori condivisi della media, `Semaphore(1, true)` FIFO e lista `CopyOnWriteArrayList` dei Monitor. **Zero attributi `static`**: ogni thread concorrente (`new Thread(() -> gestisciConnessione(socket))`) opera sull'istanza `this`. Input da console con `BufferedReader`. |
| **`ClientSensore.java`** | **Classe 2 (Centralina Sensore):** genera random 80..100 ppm, invia dato su socket effimera e si sospende per 5 secondi. |
| **`ClientMonitor.java`** | **Classe 3 (Monitor di Emergenza):** handshake persistente, ricezione allarmi push in tempo reale e formattazione a video. |
| **`avvia_simulazione.bat`** | Script batch per la compilazione e l'avvio simultaneo a 4 finestre su Windows (1 Server, 1 Monitor, 2 Centraline). |
| **`index.html`** | Applicazione web 16:9 con slide didattiche e simulatore animato interattivo con controlli di velocità (pubblicata su GitHub Pages). |
| **`Esercizio_Monitor_Inquinamento.md`** | Traccia formale da assegnare agli studenti con griglia dei requisiti e domande di verifica. |

---

## 💡 Gestione Flussi di I/O: Perché `BufferedReader` al posto di `Scanner`?
In tutti i file Java del progetto non viene utilizzata la classe `java.util.Scanner`, sostituita integralmente da `BufferedReader` avvolto su `InputStreamReader`:
1. **Coerenza concettuale**: la stessa astrazione a caratteri bufferizzati viene usata sia per i canali TCP di rete (`socket.getInputStream()`) sia per la tastiera della console (`System.in`).
2. **Prevenzione di bug di buffer**: `Scanner.nextInt()` e `Scanner.nextDouble()` non consumano il carattere di fine riga (`\n`), generando frequenti comportamenti anomali nelle letture successive. Con `BufferedReader.readLine()` ogni riga è letta in modo pulito e poi convertita (`Double.parseDouble()`).
3. **Efficienza**: `BufferedReader` legge blocchi di caratteri in modo sincronizzato e lineare, senza il pesante parsing a espressioni regolari tipico di `Scanner`.

---

## 🚀 Istruzioni per il Collaudo

### Avvio Rapido con Script Automatico
Su ambiente Windows, è sufficiente fare doppio clic sul file:
```cmd
avvia_simulazione.bat
```
Lo script:
1. Compila tutti i sorgenti con `javac -encoding UTF-8 *.java`.
2. Apre la Finestra 1 per il **Server Inquinamento** (richiede l'inserimento della soglia, es. `88.5`).
3. Avvia la Finestra 2 per il **ClientMonitor** (*Monitor-Centrale*).
4. Avvia le Finestre 3 e 4 per i 2 **ClientSensore** concorrenti (*Zona-Centro* e *Zona-Industriale*).

---

## 🖥️ Presentazione Interattiva & Simulatore Animato (16:9)

Il file `index.html` è una Single-Page Application autosufficiente (senza dipendenze esterne) ottimizzata per proiezioni didattiche:
- **Tasto `F`**: attiva/disattiva la modalità **Schermo Intero**.
- **Frecce Direzionali**: navigazione tra le 5 slide didattiche.
- **Simulatore Animato con Selettore di Velocità**:
  - Visualizza i pacchetti che viaggiano tra nodi.
  - Semaforo fisico con luci rossa e verde sincronizzate con l'acquisizione del thread.
  - Regolazione della velocità dell'animazione (🐢 0.5x, 🚶 1x didattica rallentata, ⚡ 1.8x).
  - Tasti dedicati per forzare un campionamento critico a 100 ppm e verificare l'allarme broadcast simultaneo ai monitor.
