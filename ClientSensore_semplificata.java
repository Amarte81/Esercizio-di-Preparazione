import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Random;

/**
 * Client Sensore (Centralina di Rilevamento Periferica) - Versione Semplificata.
 * Genera periodicamente valori compresi tra 80 e 100 ppm,
 * invia in due messaggi separati il comando ("SENSOR") e il dato rilevato,
 * chiude la connessione e si sospende per 5s.
 * 
 * VINCOLO SODDISFATTO: Nessun attributo statico.
 */
public class ClientSensore_semplificata {

    private final String host;
    private final int porta;
    private final String idSensore;
    private final Random random;
    private volatile boolean attivo;

    public ClientSensore_semplificata(String host, int porta, String idSensore) {
        this.host = host;
        this.porta = porta;
        this.idSensore = idSensore;
        this.random = new Random();
        this.attivo = true;
    }

    /**
     * Esegue il ciclo infinito del sensore:
     * 1. Genera numero intero random tra 80 e 100
     * 2. Apre connessione socket
     * 3. Invia comando "SENSOR" (1° messaggio)
     * 4. Invia dato di concentrazione (2° messaggio)
     * 5. Riceve ACK
     * 6. Chiude connessione
     * 7. Sleep 5 secondi
     */
    public void esegui() {
        System.out.println("Centralina sensore [" + idSensore + "] avviata. Target: " + host + ":" + porta);

        while (attivo) {
            // Genera numero intero random compreso tra 80 e 100
            int concentrazione = 80 + random.nextInt(21); // [80 .. 100]

            System.out.println("Nuovo campionamento: " + concentrazione + " ppm. Apertura connessione verso il server...");

            // Nuova connessione TCP aperta ad ogni ciclo
            try (
                Socket socket = new Socket(host, porta);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
            ) {
                // Invio del comando e del dato rilevato in DUE messaggi diversi
                out.println("SENSOR");
                out.println(concentrazione);

                // Lettura della risposta del server (ACK)
                String risposta = in.readLine();
                System.out.println("Risposta dal server: " + risposta);

            } catch (IOException e) {
                System.out.println("Errore di connessione verso il server (" + host + ":" + porta + "): " + e.getMessage());
            }

            // Chiusura della connessione avvenuta grazie al try-with-resources
            System.out.println("Connessione chiusa. Autosospensione per 5 secondi...\n");

            try {
                Thread.sleep(5000); // 5 secondi di attesa
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Sensore interrotto.");
                attivo = false;
            }
        }
    }

    public static void main(String[] args) {
        String host = "localhost";
        int porta = 8080;
        String id = "Sensore-Zona-" + ((args.length > 0) ? args[0] : "A");

        ClientSensore_semplificata sensore = new ClientSensore_semplificata(host, porta, id);
        sensore.esegui();
    }
}
