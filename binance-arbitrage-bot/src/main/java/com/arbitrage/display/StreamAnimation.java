package com.arbitrage.display;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Animación de stream en tiempo real.
 * Muestra una barra de progreso que se mueve de izquierda a derecha y viceversa.
 * Funciona como hilo daemon independiente para no bloquear el análisis.
 */
public class StreamAnimation {
    
    // =====================================================================
    // CONFIGURACIÓN DE ANIMACIÓN
    // =====================================================================
    private static final int BAR_LENGTH = 47;
    private static final long FRAME_DELAY_MS = 100;
    private static final int MIN_WIDTH = 100;
    private static final int DEFAULT_WIDTH = 120;
    
    // Códigos ANSI para colores
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";
    
    // =====================================================================
    // ESTADO (volatile para thread safety)
    // =====================================================================
    private volatile boolean running = false;
    private volatile int messageCount = 0;
    private volatile int priceMapSize = 0;
    private volatile int totalSymbols = 36;
    private volatile String status = "SCANNING";
    private volatile boolean animationStarted = false;
    
    // Hilo de animación
    private Thread animationThread;
    
    // Posición y dirección del caracter #
    private int position = 0;
    private int direction = 1; // 1 = derecha, -1 = izquierda
    
    // Lock para actualizar datos
    private final Object lock = new Object();
    
    // =====================================================================
    // CONSTRUCTOR
    // =====================================================================
    public StreamAnimation(int totalSymbols) {
        this.totalSymbols = totalSymbols;
    }
    
    public StreamAnimation() {
        this.totalSymbols = 36;
    }
    
    // =====================================================================
    // MÉTODOS PÚBLICOS
    // =====================================================================
    
    /**
     * Inicia la animación en un hilo daemon.
     */
    public void start() {
        if (running) return;
        
        running = true;
        animationStarted = false;
        position = 0;
        direction = 1;
        
        animationThread = new Thread(() -> {
            // Iniciar inmediatamente
            animationStarted = true;
            
            while (running) {
                printAnimationFrame();
                
                try {
                    Thread.sleep(FRAME_DELAY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            // Limpiar línea al final
            clearLine();
        });
        
        animationThread.setDaemon(true);
        animationThread.setName("StreamAnimation");
        animationThread.start();
    }
    
    /**
     * Detiene la animación.
     */
    public void stop() {
        running = false;
        if (animationThread != null) {
            animationThread.interrupt();
            try {
                animationThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Limpiar línea final usando método dinámico
        clearLine();
    }
    
    /**
     * Notifica cuando llega un mensaje del WebSocket.
     */
    public void onWebSocketMessage() {
        synchronized (lock) {
            messageCount++;
        }
    }
    
    /**
     * Actualiza el estado del priceMap.
     */
    public void updatePriceMap(int size) {
        synchronized (lock) {
            this.priceMapSize = size;
        }
    }
    
    /**
     * Actualiza el total de símbolos esperados.
     */
    public void setTotalSymbols(int total) {
        synchronized (lock) {
            this.totalSymbols = total;
        }
    }
    
    /**
     * Establece el estado (SCANNING, READY, STREAM_ACTIVE, etc).
     */
    public void setStatus(String newStatus) {
        this.status = newStatus;
    }
    
    /**
     * Obtiene el conteo actual de mensajes.
     */
    public int getMessageCount() {
        synchronized (lock) {
            return messageCount;
        }
    }
    
    /**
     * Obtiene el tamaño actual del priceMap.
     */
    public int getPriceMapSize() {
        synchronized (lock) {
            return priceMapSize;
        }
    }
    
    /**
     * Verifica si la animación está corriendo.
     */
    public boolean isRunning() {
        return running;
    }
    
    // =====================================================================
    // MÉTODOS PRIVADOS
    // =====================================================================
    
    /**
     * Imprime un frame de la animación.
     */
    private void printAnimationFrame() {
        if (!animationStarted) return;
        
        // Calcular posición del caracter #
        int hashPosition = Math.abs(position) % BAR_LENGTH;
        
        // Construir barra
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_LENGTH; i++) {
            if (i == hashPosition) {
                bar.append("#");
            } else {
                bar.append("=");
            }
        }
        
        // Obtener datos sincronizados
        int currentMsgCount;
        int currentPriceMapSize;
        String currentStatus;
        
        synchronized (lock) {
            currentMsgCount = messageCount;
            currentPriceMapSize = priceMapSize;
            currentStatus = status;
        }
        
        // Determinar color según estado
        String statusColor = getStatusColor(currentStatus);
        String statusIcon = getStatusIcon(currentStatus);
        
        // Construir línea completa
        String line = String.format("%s[%s %s] %s%s%s | priceMap: %d/%d | Msgs: %d",
            ANSI_CYAN,
            statusIcon,
            statusColor + currentStatus + ANSI_CYAN,
            ANSI_RESET,
            bar.toString(),
            ANSI_RESET,
            currentPriceMapSize,
            totalSymbols,
            currentMsgCount
        );
        
        // Obtener ancho de terminal y rellenar línea
        int width = getTerminalWidth();
        String paddedLine = padRight(line, width);
        
        // Imprimir con carriage return para sobreescribir en la misma línea
        System.out.print("\r" + paddedLine);
        System.out.flush();
        
        // Actualizar posición para siguiente frame
        position += direction;
        if (position >= BAR_LENGTH - 1) {
            direction = -1;
        } else if (position <= 0) {
            direction = 1;
        }
    }
    
    /**
     * Obtiene el ancho de la terminal de forma dinámica.
     */
    private int getTerminalWidth() {
        try {
            // Intentar con variable de entorno COLUMNS (Unix/Linux/Mac)
            String cols = System.getenv("COLUMNS");
            if (cols != null && !cols.isEmpty()) {
                int width = Integer.parseInt(cols.trim());
                if (width >= MIN_WIDTH) return width;
            }
            
            // Intentar con STTY (Unix/Linux)
            String[] cmd = { "sh", "-c", "tput cols 2>/dev/null || echo 0" };
            Process process = Runtime.getRuntime().exec(cmd);
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!output.isEmpty()) {
                int width = Integer.parseInt(output);
                if (width >= MIN_WIDTH) return width;
            }
        } catch (Exception e) {
            // Ignorar errores, usar default
        }
        return DEFAULT_WIDTH;
    }
    
    /**
     * Rellena una cadena con espacios hasta el ancho especificado.
     */
    private String padRight(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = text.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
    
    /**
     * Limpia la línea actual.
     */
    private void clearLine() {
        int width = getTerminalWidth();
        StringBuilder sb = new StringBuilder("\r");
        for (int i = 0; i < width; i++) {
            sb.append(' ');
        }
        sb.append("\r");
        System.out.print(sb.toString());
        System.out.flush();
    }
    
    /**
     * Obtiene el color según el estado.
     */
    private String getStatusColor(String status) {
        switch (status.toUpperCase()) {
            case "READY":
                return ANSI_GREEN;
            case "STREAM_ACTIVE":
                return ANSI_GREEN;
            case "SCANNING":
                return ANSI_YELLOW;
            case "WAITING":
                return ANSI_YELLOW;
            case "DISCONNECTED":
            case "ERROR":
                return "\u001B[31m"; // Red
            default:
                return ANSI_YELLOW;
        }
    }
    
    /**
     * Obtiene el ícono según el estado.
     */
    private String getStatusIcon(String status) {
        switch (status.toUpperCase()) {
            case "READY":
                return "✓";
            case "STREAM_ACTIVE":
                return "▶";
            case "SCANNING":
                return "▶";
            case "WAITING":
                return "⏳";
            case "DISCONNECTED":
            case "ERROR":
                return "✗";
            default:
                return "▶";
        }
    }
    
    /**
     * Resetea el contador de mensajes.
     */
    public void resetMessageCount() {
        synchronized (lock) {
            messageCount = 0;
        }
    }
    
    /**
     * Resetea el contador de mensajes.
     */
    public void setMessageCount(int count) {
        synchronized (lock) {
            this.messageCount = count;
        }
    }
}