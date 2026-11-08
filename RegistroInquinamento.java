import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * Gestisce lo stato condiviso del sistema di monitoraggio.
 * Incapsula i contatori, la soglia di allarme, il semaforo di sincronizzazione
 * e la lista dei monitor collegati.
 * 
 * NOTA ARCHITETTURALE: Nessun attributo è static!
 * L'istanza viene creata dal Server e passata per riferimento (Dependency Injection)
 * a ciascun GestoreClient.
 */
public class RegistroInquinamento {

    // Contatori condivisi per il calcolo della media
    private int numeroRilevazioni;
    private long sommaRilevazioni;
    private double mediaCorrente;
    private final double sogliaMassima;

    // Semaforo per regolare l'accesso concorrente in mutua esclusione
    private final Semaphore semaforo;

    // Lista thread-safe dei canali di output verso i Monitor registrati
    private final List<PrintWriter> monitorConnessi;

    /**
     * Costruttore del registro condiviso.
     * @param sogliaMassima Soglia massima ammissibile (tra 80 e 100).
     */
    public RegistroInquinamento(double sogliaMassima) {
        this.sogliaMassima = sogliaMassima;
        this.numeroRilevazioni = 0;
        this.sommaRilevazioni = 0;
        this.mediaCorrente = 0.0;
        // Semaforo binario (1 permesso) con politica fair (FIFO) per garantire equità tra thread
        this.semaforo = new Semaphore(1, true);
        // CopyOnWriteArrayList garantisce iterazioni thread-safe senza bloccare le scritture
        this.monitorConnessi = new CopyOnWriteArrayList<>();
    }

    /**
     * Registra un nuovo rilevamento inviato da un sensore.
     * L'accesso ai contatori e il calcolo della media avvengono in SEZIONE CRITICA protetta dal semaforo.
     * 
     * @param valore Rilevazione ricevuta (compresa tra 80 e 100).
     * @return Oggetto EsitoAggiornamento con la media aggiornata e lo stato di allarme.
     * @throws InterruptedException Se il thread viene interrotto durante l'acquisizione del semaforo.
     */
    public EsitoAggiornamento registraRilevazione(int valore) throws InterruptedException {
        boolean allarmeAttivo = false;
        double mediaCalcolata = 0.0;
        int conteggioAttuale = 0;

        // --- INIZIO SEZIONE CRITICA ---
        semaforo.acquire();
        try {
            this.numeroRilevazioni++;
            this.sommaRilevazioni += valore;
            this.mediaCorrente = (double) this.sommaRilevazioni / this.numeroRilevazioni;
            
            mediaCalcolata = this.mediaCorrente;
            conteggioAttuale = this.numeroRilevazioni;
            allarmeAttivo = (this.mediaCorrente > this.sogliaMassima);
        } finally {
            // Rilascio del semaforo nel blocco finally per garantire l'uscita in ogni caso
            semaforo.release();
        }
        // --- FINE SEZIONE CRITICA ---

        EsitoAggiornamento esito = new EsitoAggiornamento(valore, conteggioAttuale, mediaCalcolata, this.sogliaMassima, allarmeAttivo);

        // Se è scattato l'allarme, notifichiamo tutti i monitor registrati
        // L'invio delle notifiche avviene FUORI dalla sezione critica per non bloccare altri sensori
        if (allarmeAttivo) {
            inviaNotificaAllarme(esito);
        }

        return esito;
    }

    /**
     * Registra un nuovo Monitor nel registro.
     * @param out Stream PrintWriter del monitor.
     */
    public void aggiungiMonitor(PrintWriter out) {
        monitorConnessi.add(out);
    }

    /**
     * Rimuove un Monitor dal registro in caso di disconnessione o errore di I/O.
     * @param out Stream PrintWriter del monitor.
     */
    public void rimuoviMonitor(PrintWriter out) {
        monitorConnessi.remove(out);
    }

    /**
     * Trasmette un messaggio di allarme a tutti i monitor attualmente connessi.
     * @param esito Dati dell'allarme verificatosi.
     */
    private void inviaNotificaAllarme(EsitoAggiornamento esito) {
        String messaggioAllarme = String.format("ALLARME: Media=%.2f ppm > Soglia=%.2f ppm (Campione: %d | Totale rilevazioni: %d)",
                esito.getMedia(), esito.getSoglia(), esito.getUltimoValore(), esito.getNumeroCampioni());

        for (PrintWriter out : monitorConnessi) {
            try {
                out.println(messaggioAllarme);
                out.flush();
            } catch (Exception e) {
                // Se la scrittura fallisce (es. socket chiuso bruscamente), rimuoviamo il monitor
                monitorConnessi.remove(out);
            }
        }
    }

    public double getSogliaMassima() {
        return sogliaMassima;
    }

    public int getNumeroMonitorConnessi() {
        return monitorConnessi.size();
    }

    /**
     * Classe interna immutabile per incapsulare il risultato di un aggiornamento.
     */
    public static class EsitoAggiornamento {
        private final int ultimoValore;
        private final int numeroCampioni;
        private final double media;
        private final double soglia;
        private final boolean allarme;

        public EsitoAggiornamento(int ultimoValore, int numeroCampioni, double media, double soglia, boolean allarme) {
            this.ultimoValore = ultimoValore;
            this.numeroCampioni = numeroCampioni;
            this.media = media;
            this.soglia = soglia;
            this.allarme = allarme;
        }

        public int getUltimoValore() { return ultimoValore; }
        public int getNumeroCampioni() { return numeroCampioni; }
        public double getMedia() { return media; }
        public double getSoglia() { return soglia; }
        public boolean isAllarme() { return allarme; }
    }
}
