import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Client Monitor (Postazione di Supervisione / Pannello di Controllo Emergenze) - Versione Semplificata.
 * Si collega al server con una connessione persistente, invia il messaggio di registrazione "MONITOR"
 * e rimane in ascolto passivo per ricevere le notifiche di allarme in tempo reale.
 * 
 * VINCOLO SODDISFATTO: Nessun attributo statico.
 */
public class ClientMonitor_semplificata {

    private final String host;
    private final int porta;
    private final String idMonitor;
    private volatile boolean inAscolto;

    public ClientMonitor_semplificata(String host, int porta, String idMonitor) {
        this.host = host;
        this.porta = porta;
        this.idMonitor = idMonitor;
        this.inAscolto = false;
    }

    /**
     * Stabilisce una connessione persistente e si mette in ascolto continuo delle notifiche.
     */
    public void avvia() {
        this.inAscolto = true;
        System.out.println("Avvio postazione di monitoraggio [" + idMonitor + "]...");

        try (
            Socket socket = new Socket(host, porta);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            // Invio handshake di identificazione come MONITOR
            out.println("MONITOR");
            System.out.println("Messaggio di registrazione 'MONITOR' inviato. Connessione stabilita con " + host + ":" + porta);
            System.out.println("In ascolto permanente degli eventi di allarme...");
            System.out.println("------------------------------------------------------------");

            // Loop di ascolto continuo (la connessione rimane aperta)
            String messaggio;
            while (inAscolto && (messaggio = in.readLine()) != null) {
                if (messaggio.startsWith("ALLARME:")) {
                    stampaAllarmeEvidenziato(messaggio);
                } else {
                    System.out.println("Notifica ricevuta: " + messaggio);
                }
            }

        } catch (IOException e) {
            System.out.println("Connessione con il server interrotta: " + e.getMessage());
        }

        System.out.println("Postazione di monitoraggio chiusa.");
    }

    private void stampaAllarmeEvidenziato(String messaggio) {
        System.out.println("\n************************************************************");
        System.out.println(" [ALLARME RILEVATO DA " + idMonitor + "]");
        System.out.println(" >>> " + messaggio);
        System.out.println("************************************************************\n");
    }

    public static void main(String[] args) {
        String host = "localhost";
        int porta = 8080;
        String id = (args.length > 0) ? args[0] : "Monitor-Centrale-1";

        ClientMonitor_semplificata monitor = new ClientMonitor_semplificata(host, porta, id);
        monitor.avvia();
    }
}
