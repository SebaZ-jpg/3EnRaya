package Juego;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Cliente de consola del tres en raya.
 * Uso: java Cliente [host] [puerto]   (por defecto localhost 5050)
 * Un hilo escucha al servidor y otro lee el teclado, así se reciben mensajes aunque no sea tu turno.
 */
public class Cliente {
    private static volatile String miMarca = "?";

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int puerto = args.length > 1 ? Integer.parseInt(args[1]) : 5050;

        Scanner teclado = new Scanner(System.in);
        System.out.print("Tu nombre: ");
        String nombre = teclado.nextLine().trim();
        if (nombre.isEmpty()) {
            nombre = "Jugador";
        }

        try (Socket socket = new Socket(host, puerto);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("PLAY " + nombre);

            Thread receptor = new Thread(() -> escuchar(in, socket));
            receptor.setDaemon(true);
            receptor.start();

            System.out.println("Comandos: 'fila columna' (ej: 2 3), REVANCHA, SALIR");
            while (teclado.hasNextLine()) {
                String entrada = teclado.nextLine().trim();
                String mayus = entrada.toUpperCase();
                if (mayus.equals("SALIR")) {
                    out.println("BYE");
                    break;
                } else if (mayus.equals("REVANCHA")) {
                    out.println("REMATCH");
                } else if (!entrada.isEmpty()) {
                    out.println("MOVE " + entrada.replaceAll("[\\s,]+", ","));
                }
            }
        } catch (IOException e) {
            System.out.println("No se pudo conectar con el servidor: " + e.getMessage());
        }
    }

    /** Hilo receptor: interpreta los mensajes del servidor. */
    private static void escuchar(BufferedReader in, Socket socket) {
        try {
            String linea;
            while ((linea = in.readLine()) != null) {
                String[] partes = linea.split(" ", 2);
                String comando = partes[0];
                String resto = partes.length > 1 ? partes[1] : "";
                switch (comando) {
                    case "WAIT":
                        System.out.println("Esperando...");
                        break;
                    case "START":
                        miMarca = resto;
                        System.out.println("¡Empieza la partida! Tu marca es " + resto);
                        break;
                    case "OK":
                        dibujar(resto);
                        break;
                    case "TURN":
                        if (resto.equals(miMarca)) {
                            System.out.println("Es tu turno. Escribe fila y columna (1-3):");
                        } else {
                            System.out.println("Turno del rival (" + resto + ")...");
                        }
                        break;
                    case "DENIED":
                        System.out.println("Movimiento rechazado: " + resto);
                        break;
                    case "END":
                        mostrarFin(resto);
                        break;
                    case "LEFT":
                        System.out.println("El rival se ha desconectado. Fin.");
                        break;
                    case "FULL":
                        System.out.println("Partida llena: no puedes entrar.");
                        break;
                    default:
                        System.out.println("Servidor: " + linea);
                }
            }
        } catch (IOException e) {
            // conexión cerrada
        }
        System.out.println("Conexion cerrada.");
        try {
            socket.close();
        } catch (IOException ignorada) {
            // nada que hacer
        }
        System.exit(0); // el hilo principal está bloqueado leyendo el teclado
    }

    private static void mostrarFin(String resto) {
        String[] p = resto.split(" ");
        boolean abandono = p.length > 2 && p[2].equals("ABANDONO");
        if (p[0].equals("DRAW")) {
            System.out.println("Fin de la partida: EMPATE.");
        } else if (p.length > 1 && p[1].equals(miMarca)) {
            System.out.println(abandono ? "El rival abandonó. ¡Has ganado!" : "¡HAS GANADO!");
        } else {
            System.out.println("Has perdido.");
        }
        if (!abandono) {
            System.out.println("Escribe REVANCHA para jugar otra vez o SALIR.");
        }
    }

    private static void dibujar(String t) {
        System.out.println();
        System.out.println("     1   2   3");
        for (int f = 0; f < 3; f++) {
            System.out.println("  " + (f + 1) + "  " + c(t, f * 3) + " | " + c(t, f * 3 + 1) + " | " + c(t, f * 3 + 2));
            if (f < 2) {
                System.out.println("    ---+---+---");
            }
        }
        System.out.println();
    }

    private static char c(String t, int i) {
        return t.charAt(i) == '.' ? ' ' : t.charAt(i);
    }
}