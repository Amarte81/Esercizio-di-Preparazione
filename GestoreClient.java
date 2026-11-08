import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Gestore del thread associato a una singola connessione client (Sensore o Monitor).
 * Non fa uso di alcun attributo statico. Riceve lo stato condiviso per riferimento.
 */
public class GestoreClient implements Runnable {

    private final Socket socket;
    private final RegistroInquinamento registro;
    private final DateTimeFormatter timeFormatter;

    public GestoreClient(Socket socket, RegistroInquinamento registro) {
        this.socket = socket;
        this.registro = registro;
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    }

    @Override
    public void run() {
        String clientInfo = socket.getRemoteSocketAddress().toString();
        
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // Primo messaggio: identificazione del client
            String primaLinea = in.readLine();
            if (primaLinea == null) {
                return;
            }

            primaLinea = primaLinea.trim();

            if (primaLinea.startsWith("SENSOR:")) {
                // --- GESTIONE CLIENT SENSORE (Connessione Breve) ---
                gestisciSensore(primaLinea, out, clientInfo);
            } else if (primaLinea.equalsIgnoreCase("MONITOR")) {
                // --- GESTIONE CLIENT MONITOR (Connessione Persistente) ---
                gestisciMonitor(in, out, clientInfo);
            } else {
                out.println("ERRORE: Protocollo sconosciuto. Invia 'SENSOR:<valore>' o 'MONITOR'");
            }

        } catch (IOException e) {
            log("Errore di I/O con il client " + clientInfo + ": " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                log("Errore nella chiusura del socket per " + clientInfo + ": " + e.getMessage());
            }
        }
    }

    /**
     * Gestisce la logica di ricezione di un dato da un sensore.
     */
    private void gestisciSensore(String linea, PrintWriter out, String clientInfo) {
        try {
            // Estrae il valore dopo "SENSOR:"
            String valoreStr = linea.substring("SENSOR:".length()).trim();
            int valore = Integer.parseInt(valoreStr);

            if (valore < 80 || valore > 100) {
                out.println("ERRORE: Valore fuori dal range consentito [80, 100]");
                return;
            }

            // Inserimento concorrente nel registro protetto da semaforo
            RegistroInquinamento.EsitoAggiornamento esito = registro.registraRilevazione(valore);

            // Stampa log sul server
            log(String.format("DATO RICEVUTO da %s -> Valore: %d | N.Campioni: %d | Media Corrente: %.2f (Soglia: %.2f)",
                    clientInfo, valore, esito.getNumeroCampioni(), esito.getMedia(), esito.getSoglia()));

            if (esito.isAllarme()) {
                log(String.format("!!! ATTENZIONE ALLARME SUL SERVER !!! Media (%.2f) > Soglia (%.2f). Monitor avvisati: %d",
                        esito.getMedia(), esito.getSoglia(), registro.getNumeroMonitorConnessi()));
            }

            // Risposta di conferma (ACK) verso il sensore prima di chiudere
            out.println(String.format("ACK: Rilevazione registrata | Media: %.2f | Allarme: %b",
                    esito.getMedia(), esito.isAllarme()));

        } catch (NumberFormatException e) {
            out.println("ERRORE: Formato numerico non valido.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("ERRORE: Elaborazione interrotta sul server.");
        }
    }

    /**
     * Registra un Monitor e mantiene aperta la connessione fino alla sua disconnessione.
     */
    private void gestisciMonitor(BufferedReader in, PrintWriter out, String clientInfo) throws IOException {
        log("Nuovo MONITOR collegato da " + clientInfo + ". Monitor attivi totali: " + (registro.getNumeroMonitorConnessi() + 1));
        
        // Registra lo stream di output del monitor
        registro.aggiungiMonitor(out);

        // Messaggio di conferma benvenuto al monitor
        out.println(String.format("OK: Registrazione monitor completata. Soglia configurata: %.2f. In attesa di allerte...",
                registro.getSogliaMassima()));

        try {
            // Rimane bloccato in lettura. Se il monitor chiude la connessione, readLine() restituisce null o lancia IOException
            String linea;
            while ((linea = in.readLine()) != null) {
                // Eventuale comando inviato dal monitor (es. 'PING' o 'STATUS')
                if (linea.equalsIgnoreCase("PING")) {
                    out.println("PONG");
                }
            }
        } finally {
            // Quando il ciclo termina, il monitor si è disconnesso: rimozione sicura
            registro.rimuoviMonitor(out);
            log("MONITOR disconnesso da " + clientInfo + ". Monitor attivi rimanenti: " + registro.getNumeroMonitorConnessi());
        }
    }

    private void log(String messaggio) {
        String timestamp = LocalTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + Thread.currentThread().getName() + "] " + messaggio);
    }
}
