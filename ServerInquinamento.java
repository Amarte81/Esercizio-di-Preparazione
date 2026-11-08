import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * CLASSE 1: ServerInquinamento
 * Gestisce il Server centrale, i contatori condivisi della media,
 * il Semaforo di mutua esclusione e la lista dinamica dei Monitor.
 * 
 * ARCHITETTURA A SOLE 3 CLASSI:
 * 1) ServerInquinamento.java (Server centrale e stato condiviso)
 * 2) ClientSensore.java      (Simulatore centralina periferica)
 * 3) ClientMonitor.java      (Postazione di monitoraggio allarmi)
 * 
 * VINCOLI RISPETTATI:
 * - Zero attributi static: tutti i contatori, il semaforo e la lista dei monitor
 *   sono attributi d'istanza dell'oggetto ServerInquinamento.
 * - Nessun uso di Scanner: input da tastiera e da socket gestito con BufferedReader.
 */
public class ServerInquinamento {

    // Configurazione del server (attributi d'istanza, NON static)
    private final int porta;
    private final double sogliaMassima;
    private volatile boolean inEsecuzione;

    // Contatori condivisi tra i thread per il calcolo della media
    private int numeroRilevazioni;
    private long sommaRilevazioni;
    private double mediaCorrente;

    // Semaforo binario (1 permesso) con politica FIFO (fair=true) per la mutua esclusione
    private final Semaphore semaforo;

    // Lista thread-safe dei canali di output verso i Monitor collegati dinamicamente
    private final List<PrintWriter> monitorConnessi;

    // Formattatore per i timestamp dei log a console
    private final DateTimeFormatter timeFormatter;

    public ServerInquinamento(int porta, double sogliaMassima) {
        this.porta = porta;
        this.sogliaMassima = sogliaMassima;
        this.inEsecuzione = false;
        this.numeroRilevazioni = 0;
        this.sommaRilevazioni = 0;
        this.mediaCorrente = 0.0;
        this.semaforo = new Semaphore(1, true);
        this.monitorConnessi = new CopyOnWriteArrayList<>();
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    }

    /**
     * Avvia il ServerSocket e resta in ascolto infinito di connessioni da Sensori e Monitor.
     */
    public void avvia() {
        this.inEsecuzione = true;
        System.out.println("=================================================");
        System.out.println("   SERVER INQUINAMENTO ATMOSFERICO AVVIATO       ");
        System.out.println("   Porta di ascolto: " + porta);
        System.out.println("   Soglia massima allarme: " + String.format("%.2f", sogliaMassima) + " ppm");
        System.out.println("=================================================");

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            while (inEsecuzione) {
                // Attesa bloccante di una nuova connessione
                Socket socketClient = serverSocket.accept();

                // Avvio di un thread concorrente che invoca il metodo d'istanza gestisciConnessione
                // (opera direttamente sugli attributi di questo oggetto ServerInquinamento senza usare static)
                Thread threadClient = new Thread(() -> gestisciConnessione(socketClient));
                threadClient.start();
            }
        } catch (IOException e) {
            System.err.println("Errore nel ServerSocket: " + e.getMessage());
        }
    }

    /**
     * Metodo eseguito in un thread dedicato per ogni client (Sensore o Monitor) connesso.
     */
    private void gestisciConnessione(Socket socket) {
        String clientInfo = socket.getRemoteSocketAddress().toString();

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // Lettura del primo messaggio di identificazione (handshake)
            String primaLinea = in.readLine();
            if (primaLinea == null) {
                return;
            }
            primaLinea = primaLinea.trim();

            if (primaLinea.startsWith("SENSOR:")) {
                // --- GESTIONE CENTRALINA SENSORE (Connessione Breve) ---
                gestisciSensore(primaLinea, out, clientInfo);
            } else if (primaLinea.equalsIgnoreCase("MONITOR")) {
                // --- GESTIONE POSTAZIONE MONITOR (Connessione Persistente) ---
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
     * Elabora il dato inviato da un sensore aggiornando i contatori in sezione critica protetta da semaforo.
     */
    private void gestisciSensore(String linea, PrintWriter out, String clientInfo) {
        try {
            String valoreStr = linea.substring("SENSOR:".length()).trim();
            int valore = Integer.parseInt(valoreStr);

            if (valore < 80 || valore > 100) {
                out.println("ERRORE: Valore fuori dal range consentito [80, 100]");
                return;
            }

            double mediaCalcolata;
            int conteggioAttuale;
            boolean allarmeAttivo;

            // --- INIZIO SEZIONE CRITICA (Mutua Esclusione col Semaforo) ---
            semaforo.acquire();
            try {
                this.numeroRilevazioni++;
                this.sommaRilevazioni += valore;
                this.mediaCorrente = (double) this.sommaRilevazioni / this.numeroRilevazioni;

                mediaCalcolata = this.mediaCorrente;
                conteggioAttuale = this.numeroRilevazioni;
                allarmeAttivo = (this.mediaCorrente > this.sogliaMassima);
            } finally {
                // Rilascio garantito del semaforo prima di qualsiasi operazione di rete
                semaforo.release();
            }
            // --- FINE SEZIONE CRITICA ---

            log(String.format("DATO RICEVUTO da %s -> Valore: %d ppm | N.Campioni: %d | Media: %.2f ppm (Soglia: %.2f)",
                    clientInfo, valore, conteggioAttuale, mediaCalcolata, this.sogliaMassima));

            // Se la media supera la soglia, stampa l'allarme e avvisa tutti i Monitor connessi
            // L'invio in rete avviene FUORI dal semaforo per non rallentare gli altri sensori
            if (allarmeAttivo) {
                log(String.format("!!! ATTENZIONE ALLARME SUL SERVER !!! Media (%.2f) > Soglia (%.2f). Monitor avvisati: %d",
                        mediaCalcolata, this.sogliaMassima, monitorConnessi.size()));
                inviaNotificaAllarme(valore, conteggioAttuale, mediaCalcolata);
            }

            // Risposta di conferma (ACK) al sensore prima della chiusura della connessione
            out.println(String.format("ACK: Rilevazione registrata | Media: %.2f | Allarme: %b",
                    mediaCalcolata, allarmeAttivo));

        } catch (NumberFormatException e) {
            out.println("ERRORE: Formato numerico non valido.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("ERRORE: Elaborazione interrotta sul server.");
        }
    }

    /**
     * Registra dinamicamente un Monitor nella lista thread-safe e mantiene aperta la connessione.
     */
    private void gestisciMonitor(BufferedReader in, PrintWriter out, String clientInfo) throws IOException {
        monitorConnessi.add(out);
        log("Nuovo MONITOR collegato da " + clientInfo + ". Monitor attivi totali: " + monitorConnessi.size());

        out.println(String.format("OK: Registrazione monitor completata. Soglia configurata: %.2f. In attesa di allerte...",
                this.sogliaMassima));

        try {
            // Attesa passiva sulla connessione persistente finché il monitor resta collegato
            String linea;
            while ((linea = in.readLine()) != null) {
                if (linea.equalsIgnoreCase("PING")) {
                    out.println("PONG");
                }
            }
        } finally {
            // Deregistrazione automatica sicura alla chiusura del monitor
            monitorConnessi.remove(out);
            log("MONITOR disconnesso da " + clientInfo + ". Monitor attivi rimanenti: " + monitorConnessi.size());
        }
    }

    /**
     * Trasmette il messaggio di allarme in broadcast a tutti i Monitor attualmente registrati.
     */
    private void inviaNotificaAllarme(int ultimoValore, int numeroCampioni, double media) {
        String messaggioAllarme = String.format(
                "ALLARME: Media=%.2f ppm > Soglia=%.2f ppm (Campione: %d | Totale rilevazioni: %d)",
                media, this.sogliaMassima, ultimoValore, numeroCampioni);

        for (PrintWriter outMonitor : monitorConnessi) {
            try {
                outMonitor.println(messaggioAllarme);
                outMonitor.flush();
            } catch (Exception e) {
                monitorConnessi.remove(outMonitor);
            }
        }
    }

    private void log(String messaggio) {
        String timestamp = LocalTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + Thread.currentThread().getName() + "] " + messaggio);
    }

    /**
     * Entry point: unico metodo statico richiesto dalla JVM.
     * Richiede la soglia da tastiera con BufferedReader e avvia l'istanza di ServerInquinamento.
     */
    public static void main(String[] args) {
        BufferedReader lettoreTastiera = new BufferedReader(new InputStreamReader(System.in));
        int porta = 8080;
        double soglia = 0.0;

        System.out.println("=== CONFIGURAZIONE SERVER DI MONITORAGGIO ===");
        while (true) {
            System.out.print("Inserisci la soglia massima consentita di inquinante (tra 80 e 100): ");
            try {
                String input = lettoreTastiera.readLine();
                if (input == null) {
                    break;
                }
                input = input.trim();
                soglia = Double.parseDouble(input);
                if (soglia >= 80.0 && soglia <= 100.0) {
                    break;
                } else {
                    System.out.println("Attenzione: il valore deve essere compreso tra 80.0 e 100.0!");
                }
            } catch (NumberFormatException e) {
                System.out.println("Valore non valido. Inserire un numero decimale (es. 88.5).");
            } catch (IOException e) {
                System.out.println("Errore di I/O durante la lettura da tastiera: " + e.getMessage());
                break;
            }
        }

        // Creazione dell'istanza del server (zero variabili statiche di stato)
        ServerInquinamento server = new ServerInquinamento(porta, soglia);
        server.avvia();
    }
}
