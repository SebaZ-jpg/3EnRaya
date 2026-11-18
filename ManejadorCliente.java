package Juego;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Un hilo por cliente: lee sus mensajes y se los pasa a la Partida. */
public class ManejadorCliente implements Runnable {
    private final Socket socket;

    public ManejadorCliente(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        Jugador jugador = null;
        Partida partida = null;
        boolean unido = false;

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            String linea = in.readLine();
            if (linea == null || !linea.toUpperCase().startsWith("PLAY ") || linea.substring(5).trim().isEmpty()) {
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                out.println("DENIED Se esperaba PLAY <nombre>");
                return;
            }

            String nombre = linea.substring(5).trim();
            jugador = new Jugador(nombre, socket);
            partida = Servidor.partidaActual();

            boolean admitido = partida.unir(jugador);
            if (!admitido) {
                // la partida anterior pudo cerrarse justo ahora: se reintenta una vez con la vigente
                partida = Servidor.partidaActual();
                admitido = partida.unir(jugador);
            }
            if (!admitido) {
                jugador.enviar("FULL");
                System.out.println("[Servidor] Rechazado " + nombre + ": partida llena");
                return; // el finally cierra el socket
            }
            unido = true;

            while ((linea = in.readLine()) != null) {
                if (!partida.procesar(jugador, linea)) {
                    break;
                }
            }
        } catch (IOException e) {
            // Conexión caída o cerrada por el servidor: se trata igual que una desconexión
        } finally {
            if (unido) {
                partida.desconectar(jugador); // avisa al rival si procede y cierra recursos
            }
            try {
                socket.close();
            } catch (IOException ignorada) {
                // nada que hacer
            }
        }
    }
}