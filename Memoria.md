---
title: "Tres en raya online (cliente-servidor TCP)"
---

## 1. Visión general

Sistema en Java con dos programas: **Servidor** (árbitro de una única partida) y **Cliente** (consola). Se comunican por TCP con un protocolo textual de una línea por mensaje (UTF-8, terminada en `\n`). Puerto por defecto: 5050.

Clases del servidor: `Servidor` (acepta conexiones, un hilo por cliente), `ManejadorCliente` (lee los mensajes de un jugador), `Partida` (estado y reglas, compartido) y `Jugador` (nombre, marca y canal de salida).

## 2. Protocolo

El tablero viaja como 9 caracteres por filas (`X`, `O` o `.`); casillas numeradas de 1 a 3 (fila,columna).

| Mensaje | Emisor | Descripción |
|---|---|---|
| `PLAY <nombre>` | Cliente | Pide entrar en la partida (siempre el primer mensaje) |
| `WAIT` | Servidor | Falta el rival, o se espera su respuesta a una revancha |
| `FULL` | Servidor | Partida llena; se cierra la conexión |
| `START <X\|O>` | Servidor | Empieza la ronda y te asigna tu marca |
| `MOVE <fila,col>` | Cliente | Quiere marcar esa casilla |
| `OK <tablero>` | Servidor | Jugada aceptada; se envía a **ambos** jugadores (también al iniciar, con el tablero vacío) |
| `DENIED <motivo>` | Servidor | Jugada inválida (casilla ocupada, no es tu turno, fuera del tablero, formato, partida terminada); solo al que la envió |
| `TURN <X\|O>` | Servidor | De quién es el turno, a ambos |
| `END <WIN\|DRAW> [marca] [ABANDONO]` | Servidor | Fin de ronda: `END WIN X`, `END DRAW -` o `END WIN O ABANDONO` |
| `REMATCH` | Cliente / Servidor | El cliente pide revancha; el servidor lo reenvía al rival para avisarle |
| `BYE` | Cliente | Desconexión voluntaria |
| `HIST <jugadas>` | Servidor | **Añadido.** Historial de la ronda, enviado a ambos justo después de `END`: `X:1,1;O:2,1;X:1,2` |
| `LEFT` | Servidor | **Añadido.** El rival se fue cuando la partida ya había terminado |

**Ampliaciones sobre la propuesta y su justificación**

- `REMATCH` del servidor al rival: sin él, quien no ha pedido revancha no sabría que el otro está esperando.
- `LEFT`: distingue "abandono en mitad de partida" (`END WIN … ABANDONO`, el otro gana) de "se fue al acabar" (no hay ganador nuevo, solo se cierra).
- `HIST`: el servidor es quien guarda las jugadas y las envía al terminar (también por abandono), así el cliente puede revisarlas sin pedirlas y aunque la conexión se cierre justo después.
- `END` lleva la marca ganadora, suficiente para que el cliente sepa si ganó o perdió.

## 3. Diagrama de flujo de una partida

```
 Cliente A                  SERVIDOR                    Cliente B
    |--- PLAY Ana ------------>|                           |
    |<-- WAIT -----------------|  (A = X, espera)          |
    |                          |<------------ PLAY Bob ----|
    |                          |   (B = O)                 |
    |<-- START X --------------|-------------- START O --->|
    |<-- OK ......... / TURN X-|-- OK ......... / TURN X ->|
    |                          |                           |
    |--- MOVE 2,2 ------------>| valida: turno, rango,     |
    |                          | casilla libre             |
    |<-- OK tablero ---------- | ------------ OK tablero ->|
    |<-- TURN O -------------- | ------------ TURN O ----->|
    |        ... se alternan, jugada inválida => DENIED al emisor ...
    |                          | 3 en línea o tablero lleno|
    |<-- END WIN X / DRAW -----|------- END WIN X / DRAW ->|
    |                          |                           |
    |--- REMATCH ------------->|------------ REMATCH ----->|
    |<-- WAIT                  |<--------------- REMATCH --|
    |<-- START ... (nueva ronda, empieza el otro) -------->|
    |--- BYE ----------------->| al rival: LEFT, o si se
    |                          | jugaba: END WIN <m> ABANDONO
 
 Tercer cliente: PLAY --> FULL --> cierre de su conexión
```

Estados de la partida: **espera** (un jugador) → **jugando** → **terminada** (esperando revancha/BYE) → **cerrada** (alguien se fue). Al cerrarse, el servidor crea una `Partida` nueva para los siguientes clientes.

## 4. Decisiones de diseño

**Sincronización (concurrencia).** Hay un hilo por cliente y un único objeto `Partida` compartido. Todos sus métodos públicos son `synchronized`, así que validar la jugada, modificar el tablero, cambiar el turno y notificar a ambos es una operación **atómica**: dos jugadas simultáneas se serializan y ambos clientes reciben los mensajes en el mismo orden. Un `MOVE` fuera de turno nunca modifica el estado, aunque llegue a la vez que el del jugador correcto.

**El servidor es la única fuente de verdad.** El cliente no valida reglas ni lleva el tablero: solo pinta lo que recibe (`OK`). Así un cliente defectuoso o malicioso no puede hacer trampas.

**Cliente con dos hilos.** Un hilo lee el teclado y otro escucha al servidor; de esa forma se reciben el movimiento del rival, `REMATCH` o un abandono aunque no sea tu turno.

**Tercer cliente.** `Partida.unir` devuelve `false` si ya hay dos jugadores o está cerrada; el manejador envía `FULL` y cierra el socket. Si la partida se cierra justo mientras llega un cliente, se reintenta una vez con la partida vigente.

**Abandono y recursos.** Una desconexión (`BYE`, cierre del socket o `IOException`) acaba en `Partida.desconectar`, que se ejecuta en el `finally` del manejador: avisa al rival (`END WIN … ABANDONO` o `LEFT`), cierra ambos sockets y marca la partida como cerrada. Los `BufferedReader` se cierran con *try-with-resources*.

**Revancha justa.** En cada revancha empieza el otro jugador, y la marca de cada uno se mantiene durante la sesión.

**Protocolo textual.** Una línea por mensaje: es legible con `telnet` o `nc`, fácil de depurar y de probar con scripts.

## 5. Extras: cliente gráfico e historial

**Cliente gráfico (Swing)**, `ClienteGrafico`, con el mismo protocolo que el de consola (se pueden mezclar ambos). Tablero de 3x3 botones, línea de estado (turno, resultado, errores) y botones Revancha, Revisar jugadas y Salir. Un hilo receptor lee del socket y todo lo que toca la interfaz pasa al hilo de Swing con `invokeLater`; los clics solo envían `MOVE` y es el servidor quien valida.

**Historial.** `Partida` guarda en una lista cada jugada válida (`marca:fila,col`) y la reinicia en cada revancha. Al acabar la ronda (victoria, empate o abandono) la envía por `HIST` y la guarda en un fichero de texto del servidor (`historial_<fecha>_ronda<N>.txt`, con jugadores, jugadas numeradas y resultado). En el cliente gráfico, *Revisar jugadas* reconstruye el tablero jugada a jugada con ◀ y ▶ a partir de la lista recibida.

## 6. Cómo ejecutarlo

Servidor: `java Juego.Servidor [puerto]`. Cliente de consola: `java Juego.Cliente [host] [puerto]`; cliente gráfico: `java Juego.ClienteGrafico [host] [puerto] [nombre]` (uno en cada máquina). En el cliente: `fila columna` (p. ej. `2 3`), `REVANCHA`, `SALIR`.
