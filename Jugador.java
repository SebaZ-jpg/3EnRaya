package Juego;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Un jugador conectado: su nombre, su marca (X/O) y el canal de salida hacia su socket. */
public class Jugador {
    final String nombre;
    final Socket socket;
    final PrintWriter out;
    char marca = '?';

    Jugador(String nombre, Socket socket) throws IOException {
        this.nombre = nombre;
        this.socket = socket;
        // autoflush = true: cada println se envía inmediatamente
        this.out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    void enviar(String mensaje) {
        out.println(mensaje);
    }

    void cerrar() {
        try {
            socket.close();
        } catch (IOException ignorada) {
            // ya estaba cerrado
        }
    }
}