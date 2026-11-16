package Juego;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Servidor del tres en raya. Uso: java Servidor [puerto]   (por defecto 5050)
 * Gestiona una única partida a la vez; cuando termina (alguien se va) prepara una nueva.
 */
public class Servidor {
    private static final int PUERTO_POR_DEFECTO = 5050;
    private static Partida actual = new Partida();

    /** Devuelve la partida en curso; si la anterior se cerró, crea una nueva. */
    static synchronized Partida partidaActual() {
        if (actual.estaCerrada()) {
            actual = new Partida();
        }
        return actual;
    }

    public static void main(String[] args) {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : PUERTO_POR_DEFECTO;
        try (ServerSocket servidor = new ServerSocket(puerto)) {
            System.out.println("[Servidor] Escuchando en el puerto " + puerto);
            while (true) {
                Socket socket = servidor.accept();
                System.out.println("[Servidor] Conexion desde " + socket.getRemoteSocketAddress());
                new Thread(new ManejadorCliente(socket)).start();
            }
        } catch (IOException e) {
            System.err.println("[Servidor] Error: " + e.getMessage());
        }
    }
}