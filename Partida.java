package Juego;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Estado y reglas de UNA partida. Todos los métodos públicos son synchronized:
 * los dos hilos (uno por jugador) comparten este objeto y el monitor garantiza que
 * solo uno modifica el tablero o los turnos a la vez.
 */
public class Partida {
    private static final int[][] LINEAS = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},   // filas
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},   // columnas
            {0, 4, 8}, {2, 4, 6}               // diagonales
    };

    private final Jugador[] jugadores = new Jugador[2];
    private final boolean[] quiereRevancha = new boolean[2];
    private final char[] tablero = new char[9];
    private final List<String> historial = new ArrayList<>(); // jugadas de la ronda, p. ej. "X:1,1"
    private int numJugadores = 0;
    private int partidasJugadas = 0;
    private char turno = 'X';
    private boolean enJuego = false;    // se está jugando
    private boolean terminada = false;  // acabó la ronda, se espera revancha o BYE
    private boolean cerrada = false;    // la partida ya no admite más (alguien se fue)

    public synchronized boolean estaCerrada() {
        return cerrada;
    }

    /** Intenta registrar a un jugador. Devuelve false si la partida está llena o cerrada. */
    public synchronized boolean unir(Jugador j) {
        if (cerrada || numJugadores >= 2) {
            return false;
        }
        j.marca = (numJugadores == 0) ? 'X' : 'O';
        jugadores[numJugadores++] = j;
        System.out.println("[Partida] " + j.nombre + " se une como " + j.marca);
        if (numJugadores == 1) {
            j.enviar("WAIT");
        } else {
            iniciar();
        }
        return true;
    }

    /** Procesa una línea recibida de un jugador. Devuelve false si debe desconectarse. */
    public synchronized boolean procesar(Jugador j, String linea) {
        if (cerrada) {
            return false;
        }
        String[] partes = linea.trim().split("\\s+", 2);
        String comando = partes[0].toUpperCase();
        String args = partes.length > 1 ? partes[1] : "";
        switch (comando) {
            case "MOVE":
                mover(j, args);
                return true;
            case "REMATCH":
                revancha(j);
                return true;
            case "BYE":
                return false;
            default:
                j.enviar("DENIED Comando desconocido");
                return true;
        }
    }

    /** Se llama cuando un jugador se desconecta (BYE, cierre de socket o error). */
    public synchronized void desconectar(Jugador j) {
        if (cerrada) {
            return;
        }
        cerrada = true;
        System.out.println("[Partida] " + j.nombre + " se desconecta. Partida cerrada.");
        Jugador rival = rivalDe(j);
        if (rival != null) {
            if (enJuego) {
                rival.enviar("END WIN " + rival.marca + " ABANDONO");
                rival.enviar("HIST " + historialTexto());
                guardarHistorial("Gana " + rival.nombre + " (" + rival.marca + ") por abandono de " + j.nombre);
            } else {
                rival.enviar("LEFT");
            }
            rival.cerrar();
        }
        enJuego = false;
        j.cerrar();
    }

    // ---------------------------------------------------------------- privados

    private void iniciar() {
        Arrays.fill(tablero, '.');
        Arrays.fill(quiereRevancha, false);
        historial.clear();
        turno = (partidasJugadas % 2 == 0) ? 'X' : 'O'; // en cada revancha empieza el otro
        partidasJugadas++;
        enJuego = true;
        terminada = false;
        for (Jugador j : jugadores) {
            j.enviar("START " + j.marca);
        }
        broadcast("OK " + new String(tablero));
        broadcast("TURN " + turno);
        System.out.println("[Partida] Empieza la ronda " + partidasJugadas);
    }

    private void mover(Jugador j, String args) {
        if (numJugadores < 2) {
            j.enviar("DENIED Falta el segundo jugador");
            return;
        }
        if (!enJuego) {
            j.enviar("DENIED Partida terminada");
            return;
        }
        if (j.marca != turno) {
            j.enviar("DENIED No es tu turno");
            return;
        }
        String[] p = args.split(",");
        if (p.length != 2) {
            j.enviar("DENIED Formato incorrecto (usa MOVE fila,col)");
            return;
        }
        int fila, col;
        try {
            fila = Integer.parseInt(p[0].trim());
            col = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            j.enviar("DENIED Fila y columna deben ser numeros");
            return;
        }
        if (fila < 1 || fila > 3 || col < 1 || col > 3) {
            j.enviar("DENIED Casilla fuera del tablero (1-3)");
            return;
        }
        int idx = (fila - 1) * 3 + (col - 1);
        if (tablero[idx] != '.') {
            j.enviar("DENIED Casilla ocupada");
            return;
        }

        tablero[idx] = j.marca;
        historial.add(j.marca + ":" + fila + "," + col);
        broadcast("OK " + new String(tablero));

        if (hayGanador(j.marca)) {
            enJuego = false;
            terminada = true;
            broadcast("END WIN " + j.marca);
            publicarHistorial("Gana " + j.nombre + " (" + j.marca + ")");
            System.out.println("[Partida] Gana " + j.nombre + " (" + j.marca + ")");
        } else if (tableroLleno()) {
            enJuego = false;
            terminada = true;
            broadcast("END DRAW -");
            publicarHistorial("Empate");
            System.out.println("[Partida] Empate");
        } else {
            turno = (turno == 'X') ? 'O' : 'X';
            broadcast("TURN " + turno);
        }
    }

    private void revancha(Jugador j) {
        if (!terminada) {
            j.enviar("DENIED No hay ninguna partida terminada");
            return;
        }
        quiereRevancha[indiceDe(j)] = true;
        if (quiereRevancha[0] && quiereRevancha[1]) {
            iniciar();
        } else {
            j.enviar("WAIT"); // esperando a que el rival decida
            Jugador rival = rivalDe(j);
            if (rival != null) {
                rival.enviar("REMATCH"); // avisa al rival de que se le propone revancha
            }
        }
    }

    private String historialTexto() {
        return historial.isEmpty() ? "-" : String.join(";", historial);
    }

    /** Envia el historial de la ronda a ambos jugadores (HIST) y lo guarda en un fichero. */
    private void publicarHistorial(String resultado) {
        broadcast("HIST " + historialTexto());
        guardarHistorial(resultado);
    }

    private void guardarHistorial(String resultado) {
        if (historial.isEmpty()) {
            return;
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path fichero = Path.of("historial_" + ts + "_ronda" + partidasJugadas + ".txt");
        List<String> lineas = new ArrayList<>();
        lineas.add("Ronda " + partidasJugadas + ": " + jugadores[0].nombre + " vs " + jugadores[1].nombre);
        for (int i = 0; i < historial.size(); i++) {
            String[] m = historial.get(i).split(":");
            Jugador autor = jugadores[0].marca == m[0].charAt(0) ? jugadores[0] : jugadores[1];
            lineas.add((i + 1) + ". " + autor.nombre + " (" + m[0] + ") -> fila,col " + m[1]);
        }
        lineas.add("Resultado: " + resultado);
        try {
            Files.write(fichero, lineas, StandardCharsets.UTF_8);
            System.out.println("[Partida] Historial guardado en " + fichero);
        } catch (IOException e) {
            System.err.println("[Partida] No se pudo guardar el historial: " + e.getMessage());
        }
    }

    private boolean hayGanador(char marca) {
        for (int[] l : LINEAS) {
            if (tablero[l[0]] == marca && tablero[l[1]] == marca && tablero[l[2]] == marca) {
                return true;
            }
        }
        return false;
    }

    private boolean tableroLleno() {
        for (char c : tablero) {
            if (c == '.') {
                return false;
            }
        }
        return true;
    }

    private void broadcast(String mensaje) {
        for (Jugador j : jugadores) {
            if (j != null) {
                j.enviar(mensaje);
            }
        }
    }

    private int indiceDe(Jugador j) {
        return jugadores[0] == j ? 0 : 1;
    }

    private Jugador rivalDe(Jugador j) {
        return jugadores[0] == j ? jugadores[1] : jugadores[0];
    }
}