import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server Principale dell'applicazione di monitoraggio dell'inquinamento.
 * Accetta connessioni concorrenti da sensori e monitor.
 * 
 * VINCOLO SODDISFATTO: Nessun attributo statico.
 * SCELTA DI I/O: Utilizzo di BufferedReader con System.in per uniformità con i flussi di rete,
 * evitando la classe Scanner.
 */
public class ServerInquinamento {

    private final int porta;
    private final double sogliaMassima;
    private final RegistroInquinamento registro;
    private volatile boolean inEsecuzione;

    public ServerInquinamento(int porta, double sogliaMassima) {
        this.porta = porta;
        this.sogliaMassima = sogliaMassima;
        this.registro = new RegistroInquinamento(sogliaMassima);
        this.inEsecuzione = false;
    }

    /**
     * Avvia il server socket e il loop di accettazione delle connessioni.
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
                // Attesa bloccante di un client (Sensore o Monitor)
                Socket socketClient = serverSocket.accept();
                
                // Creazione del gestore per il thread dedicato
                GestoreClient gestore = new GestoreClient(socketClient, registro);
                
                // Avvio del thread concorrente
                Thread threadClient = new Thread(gestore);
                threadClient.start();
            }
        } catch (IOException e) {
            System.err.println("Errore nel ServerSocket: " + e.getMessage());
        }
    }

    public void arresta() {
        this.inEsecuzione = false;
    }

    /**
     * Entry point: unico metodo statico richiesto dalla JVM.
     * Richiede l'input all'utente ed istanzia l'oggetto server.
     * Utilizza BufferedReader per la lettura da tastiera (System.in).
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

        // Istanziazione del server con architettura OOP pulita senza attributi static
        ServerInquinamento server = new ServerInquinamento(porta, soglia);
        server.avvia();
    }
}
