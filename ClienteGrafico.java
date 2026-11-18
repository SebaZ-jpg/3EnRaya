package Juego;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente gráfico (Swing) del tres en raya. Usa el mismo protocolo que el cliente de consola.
 * Uso: java Juego.ClienteGrafico [host] [puerto] [nombre]  (si faltan, se piden en un diálogo)
 *
 * Hilos: un hilo receptor lee del socket y todo lo que toca la interfaz se ejecuta en el
 * hilo de Swing (EDT) mediante invokeLater. Los clics se envían como MOVE; el servidor decide.
 * Tras cada ronda el servidor envía HIST con todas las jugadas, que se pueden revisar paso a paso.
 */
public class ClienteGrafico extends JFrame {
    private static final Color COLOR_X = new Color(0xC62828);
    private static final Color COLOR_O = new Color(0x1565C0);

    private final Socket socket;
    private final PrintWriter out;
    private final JButton[] casillas = new JButton[9];
    private final JLabel estado = new JLabel("Conectando...", SwingConstants.CENTER);
    private final JButton btnRevancha = new JButton("Revancha");
    private final JButton btnRevisar = new JButton("Revisar jugadas");
    private final JButton btnAnterior = new JButton("◀");
    private final JButton btnSiguiente = new JButton("▶");
    private final JButton btnSalir = new JButton("Salir");

    // estado de la interfaz (solo se toca desde el EDT)
    private String miMarca = "?";
    private String tablero = ".........";
    private boolean enJuego = false;
    private boolean miTurno = false;
    private boolean conectado = true;
    private boolean revisando = false;
    private int paso = 0;
    private final List<String> historial = new ArrayList<>(); // "X:1,1"

    private ClienteGrafico(Socket socket, PrintWriter out, String nombre) {
        super("Tres en raya - " + nombre);
        this.socket = socket;
        this.out = out;

        estado.setFont(estado.getFont().deriveFont(Font.BOLD, 15f));
        estado.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel rejilla = new JPanel(new GridLayout(3, 3, 6, 6));
        rejilla.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        for (int i = 0; i < 9; i++) {
            final int fila = i / 3 + 1, col = i % 3 + 1;
            JButton b = new JButton();
            b.setFont(b.getFont().deriveFont(Font.BOLD, 54f));
            b.setFocusPainted(false);
            b.addActionListener(e -> clicCasilla(fila, col));
            casillas[i] = b;
            rejilla.add(b);
        }
        rejilla.setPreferredSize(new Dimension(360, 360));

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        botones.add(btnRevancha);
        botones.add(btnRevisar);
        botones.add(btnAnterior);
        botones.add(btnSiguiente);
        botones.add(btnSalir);

        btnRevancha.addActionListener(e -> { out.println("REMATCH"); btnRevancha.setEnabled(false); });
        btnRevisar.addActionListener(e -> alternarRevision());
        btnAnterior.addActionListener(e -> { if (paso > 0) { paso--; refrescar(); } });
        btnSiguiente.addActionListener(e -> { if (paso < historial.size()) { paso++; refrescar(); } });
        btnSalir.addActionListener(e -> salir());

        add(estado, BorderLayout.NORTH);
        add(rejilla, BorderLayout.CENTER);
        add(botones, BorderLayout.SOUTH);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { salir(); }
        });
        pack();
        setLocationRelativeTo(null);
        refrescar();
    }

    // ------------------------------------------------------------------ acciones del usuario

    private void clicCasilla(int fila, int col) {
        if (revisando || !conectado) return;
        if (!enJuego) { estado.setText("La partida no está en juego."); return; }
        if (!miTurno) { estado.setText("No es tu turno."); return; }
        out.println("MOVE " + fila + "," + col); // el servidor valida y responde OK o DENIED
    }

    private void alternarRevision() {
        revisando = !revisando;
        paso = historial.size();
        refrescar();
    }

    private void salir() {
        if (conectado) out.println("BYE");
        try { socket.close(); } catch (IOException ignorada) { /* ya cerrado */ }
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ mensajes del servidor

    private void escuchar(BufferedReader in) {
        try {
            String linea;
            while ((linea = in.readLine()) != null) {
                final String l = linea;
                SwingUtilities.invokeLater(() -> procesar(l));
            }
        } catch (IOException e) {
            // conexión cerrada
        }
        SwingUtilities.invokeLater(() -> {
            conectado = false;
            enJuego = false;
            btnRevancha.setEnabled(false);
            if (!revisando) estado.setText(estado.getText() + "  (conexión cerrada)");
            refrescar();
        });
    }

    private void procesar(String linea) {
        String[] p = linea.split(" ", 2);
        String resto = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "WAIT" -> estado.setText("Esperando al otro jugador...");
            case "START" -> {
                miMarca = resto;
                enJuego = true;
                revisando = false;
                historial.clear();
                tablero = ".........";
                estado.setText("¡Empieza la partida! Eres " + miMarca);
            }
            case "OK" -> tablero = resto;
            case "TURN" -> {
                miTurno = resto.equals(miMarca);
                estado.setText(miTurno ? "Tu turno (" + miMarca + ")" : "Turno del rival (" + resto + ")");
            }
            case "DENIED" -> estado.setText("Movimiento rechazado: " + resto);
            case "END" -> {
                enJuego = false;
                miTurno = false;
                String[] f = resto.split(" ");
                boolean abandono = f.length > 2 && f[2].equals("ABANDONO");
                if (f[0].equals("DRAW")) estado.setText("Fin: EMPATE");
                else if (f[1].equals(miMarca)) estado.setText(abandono ? "El rival abandonó: ¡has ganado!" : "¡HAS GANADO!");
                else estado.setText("Has perdido");
                btnRevancha.setEnabled(!abandono && conectado);
            }
            case "HIST" -> {
                historial.clear();
                if (!resto.equals("-")) historial.addAll(List.of(resto.split(";")));
            }
            case "REMATCH" -> estado.setText("El rival propone revancha (pulsa Revancha para aceptar)");
            case "LEFT" -> { estado.setText("El rival se ha desconectado"); btnRevancha.setEnabled(false); }
            case "FULL" -> {
                JOptionPane.showMessageDialog(this, "Partida llena: no puedes entrar.", "Rechazado", JOptionPane.WARNING_MESSAGE);
                System.exit(0);
            }
            default -> estado.setText(linea);
        }
        refrescar();
    }

    // ------------------------------------------------------------------ pintado

    /** Reconstruye el tablero tras aplicar las primeras n jugadas del historial. */
    private String tableroEnPaso(int n) {
        char[] t = ".........".toCharArray();
        for (int i = 0; i < n; i++) {
            String[] m = historial.get(i).split("[:,]");
            t[(Integer.parseInt(m[1]) - 1) * 3 + (Integer.parseInt(m[2]) - 1)] = m[0].charAt(0);
        }
        return new String(t);
    }

    private void refrescar() {
        String t = revisando ? tableroEnPaso(paso) : tablero;
        for (int i = 0; i < 9; i++) {
            char c = t.charAt(i);
            casillas[i].setText(c == '.' ? "" : String.valueOf(c));
            casillas[i].setForeground(c == 'X' ? COLOR_X : COLOR_O);
        }
        if (revisando) {
            estado.setText(paso == 0 ? "Revisión: tablero vacío (0 de " + historial.size() + ")"
                    : "Revisión: jugada " + paso + " de " + historial.size() + " (" + historial.get(paso - 1).replace(":", " en ") + ")");
        }
        btnRevisar.setEnabled(!enJuego && !historial.isEmpty());
        btnRevisar.setText(revisando ? "Volver" : "Revisar jugadas");
        btnAnterior.setEnabled(revisando && paso > 0);
        btnSiguiente.setEnabled(revisando && paso < historial.size());
    }

    // ------------------------------------------------------------------ arranque

    public static void main(String[] args) {
        JTextField campoNombre = new JTextField(args.length > 2 ? args[2] : "Jugador");
        JTextField campoHost = new JTextField(args.length > 0 ? args[0] : "localhost");
        JTextField campoPuerto = new JTextField(args.length > 1 ? args[1] : "5050");
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Nombre:"));   form.add(campoNombre);
        form.add(new JLabel("Servidor:")); form.add(campoHost);
        form.add(new JLabel("Puerto:"));   form.add(campoPuerto);
        if (JOptionPane.showConfirmDialog(null, form, "Conectar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String nombre = campoNombre.getText().trim().isEmpty() ? "Jugador" : campoNombre.getText().trim();
        try {
            Socket socket = new Socket(campoHost.getText().trim(), Integer.parseInt(campoPuerto.getText().trim()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            SwingUtilities.invokeLater(() -> {
                ClienteGrafico ventana = new ClienteGrafico(socket, out, nombre);
                ventana.setVisible(true);
                Thread receptor = new Thread(() -> ventana.escuchar(in));
                receptor.setDaemon(true);
                receptor.start();
                out.println("PLAY " + nombre);
            });
        } catch (IOException | NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "No se pudo conectar: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
