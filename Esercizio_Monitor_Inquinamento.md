# Esercitazione TPSIT: Sistema Distribuito di Monitoraggio Ambientale e Notifica Allarmi

## Contesto e Scenario
In un polo industriale urbano è necessario monitorare costantemente i livelli di inquinamento atmosferico (es. concentrazione di particolato sottile $PM_{10}$ o $NO_2$) rilevati in differenti zone della città.  
Per garantire tempestività d'intervento, la municipalità richiede un'infrastruttura client-server distribuita e concorrente, in grado di aggregare i dati provenienti da molteplici sensori periferici e di allertare in tempo reale postazioni di supervisione (**Monitor di Emergenza**).

---

## Specifiche dei Requisiti

### 1. Client Sensore (Centraline Periferiche)
Ogni client simula una centralina di rilevamento posta in una zona geografica distinta:
- **Generazione dato**: genera ciclicamente un numero intero random compreso tra **80** e **100** ppm, rappresentativo della concentrazione dell'inquinante.
- **Ciclo di vita & Connessione**:
  1. Si risveglia e apre una nuova connessione socket verso il Server.
  2. Identifica il proprio ruolo inviando il comando di registrazione/invio (es. `SENSOR:<valore>` oppure identificandosi come `SENSOR` seguito dal dato).
  3. Riceve l'eventuale conferma di ricezione (ACK) dal server.
  4. Chiude immediatamente la connessione socket.
  5. Entra in sospensione (`Thread.sleep`) per **5 secondi**.
  6. Ripete il ciclo all'infinito finché il processo non viene arrestato.
- **Scalabilità**: il sistema deve consentire l'esecuzione concorrente di più client sensore contemporaneamente (es. Zona Centro, Zona Industriale, Zona Nord, ecc.).

### 2. Client Monitor (Stazioni di Controllo & Allarme)
I Monitor rappresentano postazioni di visualizzazione/supervisione (es. comando di Polizia Locale, Protezione Civile, bacheche informative comunali):
- **Ciclo di vita & Connessione persistente**:
  1. All'avvio, apre una connessione socket verso il Server.
  2. Si identifica come postazione di monitoraggio inviando il comando `MONITOR`.
  3. Riceve dal server la conferma di registrazione e lo stato iniziale (soglia impostata).
  4. **Rimane connesso in ascolto continuo** (connessione persistente / long-lived socket) su uno stream di input.
  5. Quando il server rileva che la media globale supera la soglia consentita, il Monitor riceve tempestivamente un messaggio di allarme formattato (es. timestamp, media corrente, soglia superata) e lo mostra a video con adeguato risalto grafico/testuale.
  6. Gestisce la terminazione pulita in caso di interruzione.

### 3. Server Centrale (Elaborazione & Allarme)
Il server centrale coordina l'intera infrastruttura:
- **Configurazione iniziale**: all'avvio richiede all'amministratore (tramite input da tastiera/console) il valore della **soglia massima di allarme** (numero compreso tra 80 e 100).
- **Accettazione concorrente multithread**:
  - Resta in esecuzione infinita in ascolto su una porta TCP (es. `8080`).
  - Per ogni connessione in arrivo, delega la gestione a un thread concorrente indipendente (`Runnable` o sottoclasse di `Thread`).
- **Sincronizzazione e Gestione della Concorrenza**:
  - Mantiene in memoria contatori condivisi:
    - Numero totale di rilevazioni pervenute ($N$);
    - Somma cumulativa delle rilevazioni;
    - Media ponderata cumulativa: $\text{Media} = \frac{\text{Somma}}{N}$.
  - **Semaforo di mutua esclusione**: l'accesso e la modifica dei contatori condivisi e il ricalcolo della media devono essere rigorosamente protetti da un **semaforo** (`java.util.concurrent.Semaphore`), garantendo la corretta sincronizzazione tra i thread dei sensori che scrivono concorrentemente.
- **Gestione dei Monitor Registrati**:
  - Mantiene un elenco thread-safe delle connessioni dei monitor attivi.
  - Quando un sensore aggiorna la media:
    - Se $\text{Media} > \text{Soglia}$, il server stampa a console un evidente messaggio di allarme e notifica **tutti i monitor attualmente collegati** inviando loro il messaggio di allarme.
    - Se un monitor si disconnette, il server rimuove in modo sicuro la connessione dalla lista evitando eccezioni.
- **Log ed Output**: ogni ricezione, variazione della media e notifica di allarme deve essere chiaramente loggata a console con timestamp e identificativo del thread.

---

## Vincoli di Progettazione e Sviluppo
1. **Linguaggio**: Java (Standard Edition).
2. **Architettura OOP pulita**:
   - **TASSATIVO**: **Evitare attributi `static`** per lo stato del server, i contatori, il semaforo o la lista dei monitor.
   - Creare una classe dedicata (es. `RegistroInquinamento` o `StatoCondiviso`) che incapsuli i contatori, il semaforo e i metodi sincronizzati, passata per riferimento (Dependency Injection) ai gestori dei thread.
   - L'unico metodo `static` consentito è `public static void main(String[] args)` come punto di ingresso per avviare l'oggetto applicativo.
3. **Robustezza**:
   - Chiusura corretta dei flussi (`BufferedReader`, `PrintWriter`, `Socket`) mediante costrutti try-with-resources o blocchi finally.
   - Tolleranza alla disconnessione improvvisa di un monitor o di un sensore.
4. **Flussi di I/O Uniformi (Nessun uso di `Scanner`)**:
   - Per la lettura da tastiera e dai socket di rete utilizzare esclusivamente `BufferedReader` (con `InputStreamReader`), senza ricorrere alla classe `Scanner`. Ciò assicura coerenza strutturale nell'uso dei flussi a caratteri ed evita bug tipici di buffer residui (`\n`).

---

## Schema del Protocollo Applicativo
```
+-------------+                    +-----------------+                    +---------------+
| SENSOR-01   |                    |     SERVER      |                    |    MONITOR    |
+-------------+                    +-----------------+                    +---------------+
       |                                    |                                     |
       |                                    |<====== Connessione TCP =============|
       |                                    |<------ "MONITOR\n" -----------------|
       |                                    |------> "OK:REGISTRATO\n" ---------->| (In ascolto...)
       |                                    |                                     |
       |===== Connessione TCP =============>|                                     |
       |----- "SENSOR:94\n" --------------->| [Acquisisce Semaforo]               |
       |                                    | [Aggiorna Somma e N]                |
       |                                    | [Calcola Media]                     |
       |                                    | [Rilascia Semaforo]                 |
       |                                    | Se Media > Soglia:                  |
       |<---- "ACK:DATO_RICEVUTO\n" --------| - Stampa Allarme Console            |
       |===== Chiusura TCP =================| - Invia a tutti i monitor --------->| [ALLARME!]
       |                                    |                                     |
       | [Sleep 5 secondi]                  |                                     |
```

---

## Approfondimento Didattico: Il Processo di Registrazione Dinamica dei Monitor

La registrazione dinamica dei Monitor è basata sul pattern **Publish-Subscribe / Observer** applicato ai sistemi distribuiti mediante Socket TCP.

### 1. Dinamicità e Hot-Plugging
I Monitor possono connettersi o disconnettersi in qualunque momento della vita del server:
- All'avvio, la lista dei monitor è vuota (`monitorConnessi.isEmpty()`).
- Quando una nuova postazione si collega e invia `MONITOR`, il thread dedicato estrae lo stream di scrittura `PrintWriter out` e lo registra in modo atomico.
- Il server non deve mai essere riavviato per aggiungere o togliere postazioni di controllo.

### 2. Struttura Dati Concorrente: `CopyOnWriteArrayList`
Per memorizzare i riferimenti ai monitor attivi si utilizza `java.util.concurrent.CopyOnWriteArrayList<PrintWriter>`.
- In ambienti concorrenti tradizionali, iterare su una `ArrayList` mentre un altro thread aggiunge o rimuove un elemento genera l'eccezione non controllata `ConcurrentModificationException`.
- `CopyOnWriteArrayList` garantisce che le operazioni di lettura (iterazione per l'invio delle notifiche di allarme) avvengano su uno snapshot immutabile dell'array, senza richiedere lock o bloccare il calcolo dei sensori.

### 3. Disaccoppiamento tra Sezione Critica e Notifiche di Rete
Un errore frequente nella programmazione distribuita consiste nell'inviare dati di rete all'interno di una sezione critica protetta da semaforo.
Nel nostro progetto:
1. Il semaforo arbitra **esclusivamente** la lettura e scrittura dei contatori di memoria ($N$, somma, media).
2. Appena calcolata la media, il semaforo viene **immediatamente rilasciato** nel blocco `finally`.
3. Solo successivamente, se la condizione di allarme è verificata, viene invocato `inviaNotificaAllarme()`.
In questo modo, la presenza di monitor lenti, reti con elevata latenza o disconnessioni impreviste **non rallenta né blocca** l'acquisizione dei campioni provenienti dagli altri sensori.

### 4. Tolleranza ai Guasti e Deregistrazione Automatica
La deregistrazione di un monitor può avvenire in due modalità:
- **Disconnessione ordinata o rilevata**: il monitor chiude il socket; il ciclo `while (in.readLine() != null)` si sblocca restituendo `null` e il blocco `finally` esegue `registro.rimuoviMonitor(out)`.
- **Rottura improvvisa durante l'invio**: se un monitor cade mentre il server sta inviando un allarme, il metodo `inviaNotificaAllarme()` intercetta l'eccezione o l'errore sullo stream e rimuove tempestivamente il `PrintWriter` non più valido.

---

## Domande di Verifica e Competenze
1. Spiega la differenza tra il ciclo di vita della connessione TCP del Sensore (short-lived) e quello del Monitor (long-lived).
2. Perché è indispensabile proteggere la sezione critica di calcolo della media con un Semaforo e cosa accadrebbe in assenza di sincronizzazione (Race Condition)?
3. Come funziona la registrazione dinamica dei Monitor e perché l'uso di `CopyOnWriteArrayList` previene le `ConcurrentModificationException`?
4. Perché la trasmissione delle notifiche di allarme ai Monitor deve avvenire tassativamente fuori dalla sezione critica del Semaforo?
5. Perché l'approccio Object-Oriented senza attributi `static` è preferibile per testabilità, modularità e manutenzione del software?
6. Perché nell'architettura è preferibile usare `BufferedReader` con `System.in` invece di `Scanner`?
