import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Client Monitor (Postazione di Supervisione / Pannello di Controllo Emergenze).
 * Si collega al server con una connessione persistente, invia il messaggio di registrazione "MONITOR"
 * e rimane in ascolto passivo per ricevere le notifiche di allarme in tempo reale.
 * 
 * VINCOLO SODDISFATTO: Nessun attributo statico.
 */
public class ClientMonitor {

    private final String host;
    private final int porta;
    private final String idMonitor;
    private final DateTimeFormatter timeFormatter;
    private volatile boolean inAscolto;

    public ClientMonitor(String host, int porta, String idMonitor) {
        this.host = host;
        this.porta = porta;
        this.idMonitor = idMonitor;
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        this.inAscolto = false;
    }

    /**
     * Stabilisce una connessione persistente e si mette in ascolto continuo delle notifiche.
     */
    public void avvia() {
        this.inAscolto = true;
        log("Avvio postazione di monitoraggio [" + idMonitor + "]...");

        try (
            Socket socket = new Socket(host, porta);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            // Invio handshake di identificazione come MONITOR
            out.println("MONITOR");
            log("Messaggio di registrazione 'MONITOR' inviato. Connessione stabilita con " + host + ":" + porta);

            // Lettura del messaggio di conferma iniziale
            String conferma = in.readLine();
            log("Stato server: " + conferma);
            log("In ascolto permanente degli eventi di allarme...");
            System.out.println("------------------------------------------------------------");

            // Loop di ascolto continuo (la connessione rimane aperta)
            String messaggio;
            while (inAscolto && (messaggio = in.readLine()) != null) {
                if (messaggio.startsWith("ALLARME:")) {
                    stampaAllarmeEvidenziato(messaggio);
                } else {
                    log("Notifica ricevuta: " + messaggio);
                }
            }

        } catch (IOException e) {
            log("Connessione con il server interrotta: " + e.getMessage());
        } finally {
            log("Postazione di monitoraggio chiusa.");
        }
    }

    private void stampaAllarmeEvidenziato(String messaggio) {
        String timestamp = LocalTime.now().format(timeFormatter);
        System.out.println("\n************************************************************");
        System.out.println(" [" + timestamp + "] [ALLARME RILEVATO DA " + idMonitor + "]");
        System.out.println(" >>> " + messaggio);
        System.out.println(" Azione raccomandata: Attivare protocollo emergenza smog!");
        System.out.println("************************************************************\n");
    }

    private void log(String messaggio) {
        String timestamp = LocalTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + idMonitor + "] " + messaggio);
    }

    public static void main(String[] args) {
        String host = "localhost";
        int porta = 8080;
        String id = (args.length > 0) ? args[0] : "Monitor-Centrale-1";

        ClientMonitor monitor = new ClientMonitor(host, porta, id);
        monitor.avvia();
    }
}
