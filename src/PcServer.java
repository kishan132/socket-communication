import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A standalone, multi-client TCP server that runs on your PC.
 *
 * It is deliberately dependency-free plain Java so you can run it anywhere a
 * JDK is installed, without Gradle or Android:
 *
 *     javac PcServer.java
 *     java PcServer          (listens on the default port 5000)
 *     java PcServer 6000     (listens on port 6000)
 *
 * What it does:
 *   1. Opens a ServerSocket that listens on a TCP port.
 *   2. For every phone/client that connects, it spawns a dedicated thread so
 *      many clients can be served at the same time.
 *   3. Every line a client sends is echoed back to that client AND broadcast
 *      to all other connected clients (a tiny chat room).
 *   4. Anything you type in the server console is broadcast to all clients.
 */
public class PcServer {

    private static final int DEFAULT_PORT = 5000;

    // A thread-safe set of all currently connected clients, used for broadcast.
    private static final Set<ClientHandler> clients =
            Collections.synchronizedSet(new HashSet<ClientHandler>());

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port '" + args[0] + "', using " + DEFAULT_PORT);
            }
        }

        // try-with-resources guarantees the ServerSocket is closed on exit.
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            printBanner(port);

            // Read console input on a side thread so the main thread can keep
            // accepting new client connections.
            startConsoleBroadcaster();

            // The classic accept loop: accept() BLOCKS until a client connects,
            // then returns a new Socket dedicated to that single client.
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.add(handler);
                handler.start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    /** Send a message to every connected client. */
    private static void broadcast(String message, ClientHandler except) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != except) {
                    client.send(message);
                }
            }
        }
    }

    private static void startConsoleBroadcaster() {
        Thread t = new Thread(() -> {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            try {
                String line;
                while ((line = console.readLine()) != null) {
                    broadcast("[SERVER] " + line, null);
                }
            } catch (IOException ignored) {
            }
        }, "console-broadcaster");
        t.setDaemon(true);
        t.start();
    }

    private static void printBanner(int port) throws IOException {
        String host = InetAddress.getLocalHost().getHostAddress();
        System.out.println("========================================");
        System.out.println(" Pc Socket Server is running");
        System.out.println(" Listening on port : " + port);
        System.out.println(" This PC's LAN IP  : " + host);
        System.out.println(" Point the Android app at " + host + ":" + port);
        System.out.println(" Type here + Enter to broadcast to clients");
        System.out.println("========================================");
    }

    /**
     * Handles a single connected client on its own thread.
     * Extends Thread; the read loop lives in run().
     */
    private static class ClientHandler extends Thread {
        private final Socket socket;
        private final String id;
        private PrintWriter writer;

        ClientHandler(Socket socket) {
            this.socket = socket;
            this.id = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        @Override
        public void run() {
            System.out.println("[+] Client connected: " + id);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))) {

                // autoFlush=true -> every println() is pushed onto the wire.
                writer = new PrintWriter(socket.getOutputStream(), true);
                send("Welcome! You are " + id);
                broadcast("[SERVER] " + id + " joined.", this);

                // readLine() blocks until a full '\n'-terminated line arrives,
                // or returns null when the client closes the connection.
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(id + " > " + line);
                    send("echo: " + line);           // reply to the sender
                    broadcast(id + ": " + line, this); // relay to everyone else
                }
            } catch (IOException e) {
                System.out.println("[!] " + id + " error: " + e.getMessage());
            } finally {
                clients.remove(this);
                broadcast("[SERVER] " + id + " left.", this);
                System.out.println("[-] Client disconnected: " + id);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        /** Thread-safe write to this client. */
        synchronized void send(String message) {
            if (writer != null) {
                writer.println(message);
            }
        }
    }
}
