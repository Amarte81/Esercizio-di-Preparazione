import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Client Sensore (Centralina di Rilevamento Periferica).
 * Genera periodicamente valori compresi tra 80 e 100 ppm,
 * apre una connessione effimera al server, invia il dato, la chiude e si sospende per 5s.
 * 
 * VINCOLO SODDISFATTO: Nessun attributo statico.
 */
public class ClientSensore {

    private final String host;
    private final int porta;
    private final String idSensore;
    private final Random random;
    private final DateTimeFormatter timeFormatter;
    private volatile boolean attivo;

    public ClientSensore(String host, int porta, String idSensore) {
        this.host = host;
        this.porta = porta;
        this.idSensore = idSensore;
        this.random = new Random();
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        this.attivo = true;
    }

    /**
     * Esegue il ciclo infinito del sensore:
     * 1. Genera numero intero random tra 80 e 100
     * 2. Apre connessione socket
     * 3. Invia dato
     * 4. Riceve ACK
     * 5. Chiude connessione
     * 6. Sleep 5 secondi
     */
    public void esegui() {
        log("Centralina sensore [" + idSensore + "] avviata. Target: " + host + ":" + porta);

        while (attivo) {
            // Genera numero intero random compreso tra 80 e 100
            int concentrazione = 80 + random.nextInt(21); // [80 .. 100]

            log(String.format("Nuovo campionamento: %d ppm. Apertura connessione verso il server...", concentrazione));

            // Nuova connessione TCP aperta ad ogni ciclo
            try (
                Socket socket = new Socket(host, porta);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
            ) {
                // Invio del comando col dato rilevato
                out.println("SENSOR:" + concentrazione);

                // Lettura della risposta del server (ACK)
                String risposta = in.readLine();
                log("Risposta dal server: " + risposta);

            } catch (IOException e) {
                log("Errore di connessione verso il server (" + host + ":" + porta + "): " + e.getMessage());
            }

            // Chiusura della connessione avvenuta grazie al try-with-resources
            log("Connessione chiusa. Autosospensione per 5 secondi...\n");

            try {
                Thread.sleep(5000); // 5 secondi di attesa
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("Sensore interrotto.");
                attivo = false;
            }
        }
    }

    private void log(String messaggio) {
        String timestamp = LocalTime.now().format(timeFormatter);
        System.out.println("[" + timestamp + "] [" + idSensore + "] " + messaggio);
    }

    public static void main(String[] args) {
        String host = "localhost";
        int porta = 8080;
        String id = "Sensore-Zona-" + ((args.length > 0) ? args[0] : "A");

        ClientSensore sensore = new ClientSensore(host, porta, id);
        sensore.esegui();
    }
}
