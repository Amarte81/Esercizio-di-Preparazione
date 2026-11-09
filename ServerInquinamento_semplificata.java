import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * CLASSE 1: ServerInquinamento_semplificata
 * Gestisce il Server centrale, i contatori condivisi della media,
 * il Semaforo di mutua esclusione e la lista dinamica dei Monitor.
 * 
 * ARCHITETTURA A SOLE 3 CLASSI (Versione Semplificata):
 * 1) ServerInquinamento_semplificata.java (Server centrale e stato condiviso)
 * 2) ClientSensore_semplificata.java      (Simulatore centralina periferica)
 * 3) ClientMonitor_semplificata.java      (Postazione di monitoraggio allarmi)
 * 
 * VINCOLI RISPETTATI:
 * - Zero attributi static: tutti i contatori, il semaforo e la lista dei monitor
 *   sono attributi d'istanza dell'oggetto ServerInquinamento_semplificata.
 * - Nessun uso di Scanner: input da tastiera e da socket gestito con BufferedReader.
 * - Semplici System.out.println al posto di log formattati complessi.
 * - Protocollo sensore: comando e dato inviati in due messaggi distinti.
 * - Monitor: gestione e registrazione diretta tramite Socket.
 */
public class ServerInquinamento_semplificata {

    // Configurazione del server (attributi d'istanza, NON static)
    private final int porta;
    private final double sogliaMassima;

    // Contatori condivisi tra i thread per il calcolo della media
    private int numeroRilevazioni;
    private long sommaRilevazioni;
    private double mediaCorrente;

    // Semaforo binario (1 permesso) con politica FIFO (fair=true) per la mutua esclusione
    private final Semaphore semaforo;

    // Lista thread-safe dei Socket verso i Monitor collegati dinamicamente
    private final List<Socket> monitorConnessi;

    public ServerInquinamento_semplificata(int porta, double sogliaMassima) {
        this.porta = porta;
        this.sogliaMassima = sogliaMassima;
        this.numeroRilevazioni = 0;
        this.sommaRilevazioni = 0;
        this.mediaCorrente = 0.0;
        this.semaforo = new Semaphore(1, true);
        this.monitorConnessi = new CopyOnWriteArrayList<>();
    }

    /**
     * Avvia il ServerSocket e resta in ascolto di connessioni da Sensori e Monitor.
     */
    public void avvia() {
        System.out.println("=================================================");
        System.out.println("   SERVER INQUINAMENTO ATMOSFERICO AVVIATO       ");
        System.out.println("   Porta di ascolto: " + porta);
        System.out.println("   Soglia massima allarme: " + String.format("%.2f", sogliaMassima) + " ppm");
        System.out.println("=================================================");

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            while (true) {
                // Attesa bloccante di una nuova connessione
                Socket socketClient = serverSocket.accept();

                // Avvio di un thread concorrente con classe anonima Runnable
                Thread threadClient = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        gestisciConnessione(socketClient);
                    }
                });
                threadClient.start();
            }
        } catch (IOException e) {
            System.out.println("Errore nel ServerSocket: " + e.getMessage());
        }
    }

    /**
     * Metodo eseguito in un thread dedicato per ogni client connesso.
     */
    private void gestisciConnessione(Socket socket) {
        String clientInfo = socket.getRemoteSocketAddress().toString();

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // Lettura del primo messaggio (comando o tipo di client)
            String primaLinea = in.readLine();
            if (primaLinea == null) {
                return;
            }
            primaLinea = primaLinea.trim();

            if (primaLinea.equalsIgnoreCase("SENSOR")) {
                // --- GESTIONE SENSORE: ricezione del secondo messaggio con il dato rilevato ---
                String secondaLinea = in.readLine();
                gestisciSensore(secondaLinea, out, clientInfo);
            } else if (primaLinea.equalsIgnoreCase("MONITOR")) {
                // --- GESTIONE POSTAZIONE MONITOR (Connessione Persistente, uso diretto di Socket) ---
                gestisciMonitor(socket, in, out, clientInfo);
            } else {
                out.println("ERRORE: Protocollo sconosciuto. Invia 'SENSOR' seguito dal valore, oppure 'MONITOR'");
            }

        } catch (IOException e) {
            System.out.println("Errore di I/O con il client " + clientInfo + ": " + e.getMessage());
        }
    }

    /**
     * Elabora il dato inviato da un sensore aggiornando i contatori in sezione critica protetta da semaforo.
     */
    private void gestisciSensore(String valoreStr, PrintWriter out, String clientInfo) {
        if (valoreStr == null) {
            out.println("ERRORE: Valore del sensore mancante.");
            return;
        }

        try {
            int valore = Integer.parseInt(valoreStr.trim());

            if (valore < 80 || valore > 100) {
                out.println("ERRORE: Valore fuori dal range consentito [80, 100]");
                return;
            }

            double mediaCalcolata;
            int conteggioAttuale;
            boolean allarmeAttivo;

            // --- INIZIO SEZIONE CRITICA (Mutua Esclusione col Semaforo) ---
            semaforo.acquire();
            this.numeroRilevazioni++;
            this.sommaRilevazioni += valore;
            this.mediaCorrente = (double) this.sommaRilevazioni / this.numeroRilevazioni;
            mediaCalcolata = this.mediaCorrente;
            conteggioAttuale = this.numeroRilevazioni;
            allarmeAttivo = (this.mediaCorrente > this.sogliaMassima);
            semaforo.release();
            // --- FINE SEZIONE CRITICA ---

            System.out.println(String.format("DATO RICEVUTO da %s -> Valore: %d ppm | N.Campioni: %d | Media: %.2f ppm (Soglia: %.2f)",
                    clientInfo, valore, conteggioAttuale, mediaCalcolata, this.sogliaMassima));

            // Se la media supera la soglia, stampa l'allarme e avvisa tutti i Monitor connessi
            if (allarmeAttivo) {
                System.out.println(String.format("!!! ATTENZIONE ALLARME SUL SERVER !!! Media (%.2f) > Soglia (%.2f). Monitor avvisati: %d",
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
     * Registra dinamicamente la Socket del Monitor nella lista e mantiene aperta la connessione.
     */
    private void gestisciMonitor(Socket socket, BufferedReader in, PrintWriter out, String clientInfo) throws IOException {
        monitorConnessi.add(socket);
        System.out.println("Nuovo MONITOR collegato da " + clientInfo + ". Monitor attivi totali: " + monitorConnessi.size());

        out.println(String.format("OK: Registrazione monitor completata. Soglia configurata: %.2f. In attesa di allerte...",
                this.sogliaMassima));

        // Attesa passiva sulla connessione persistente finché il monitor resta collegato
        String linea;
        while ((linea = in.readLine()) != null) {
            if (linea.equalsIgnoreCase("PING")) {
                out.println("PONG");
            }
        }

        // Deregistrazione della Socket alla chiusura del monitor
        monitorConnessi.remove(socket);
        System.out.println("MONITOR disconnesso da " + clientInfo + ". Monitor attivi rimanenti: " + monitorConnessi.size());
    }

    /**
     * Trasmette il messaggio di allarme in broadcast a tutte le Socket dei Monitor attualmente registrati.
     */
    private void inviaNotificaAllarme(int ultimoValore, int numeroCampioni, double media) {
        String messaggioAllarme = String.format(
                "ALLARME: Media=%.2f ppm > Soglia=%.2f ppm (Campione: %d | Totale rilevazioni: %d)",
                media, this.sogliaMassima, ultimoValore, numeroCampioni);

        for (Socket socketMonitor : monitorConnessi) {
            try {
                PrintWriter outMonitor = new PrintWriter(socketMonitor.getOutputStream(), true);
                outMonitor.println(messaggioAllarme);
            } catch (IOException e) {
                monitorConnessi.remove(socketMonitor);
            }
        }
    }

    /**
     * Entry point: unico metodo statico richiesto dalla JVM.
     * Richiede la soglia da tastiera con BufferedReader e avvia l'istanza.
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
        ServerInquinamento_semplificata server = new ServerInquinamento_semplificata(porta, soglia);
        server.avvia();
    }
}
