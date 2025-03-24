import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class AuctionServer {
    private static final int PORT = 8888;
    private static final long AUCTION_DURATION = 5 * 60 * 1000; // 5 minutes in milliseconds

    private ServerSocket serverSocket;
    private Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private Map<String, Product> products = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    public static void main(String[] args) {
        AuctionServer server = new AuctionServer();
        server.start();
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Auction Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClientConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClientConnection(Socket clientSocket) {
        try {
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // Get client's name
            String clientName = in.readLine();

            // Check if the name is already used
            if (clients.containsKey(clientName)) {
                out.println("ERROR: Name already in use. Connection refused.");
                clientSocket.close();
                return;
            }

            // Client accepted
            ClientHandler clientHandler = new ClientHandler(clientName, clientSocket, this);
            clients.put(clientName, clientHandler);

            // Send product list
            sendProductList(clientHandler);

            // Start the client handler thread
            new Thread(clientHandler).start();

        } catch (IOException e) {
            System.err.println("Error handling client connection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void addProduct(String clientName, String productName, double minimumPrice) {
        // Check if product name is unique for this client
        for (Product product : products.values()) {
            if (product.getOwner().equals(clientName) && product.getName().equals(productName)) {
                ClientHandler client = clients.get(clientName);
                if (client != null) {
                    client.sendMessage("ERROR: You already have a product with this name.");
                }
                return;
            }
        }

        // Create new product
        String productId = clientName + ":" + productName;
        Product product = new Product(productId, productName, clientName, minimumPrice);
        products.put(productId, product);

        // Schedule auction end
        scheduler.schedule(() -> endAuction(productId), AUCTION_DURATION, TimeUnit.MILLISECONDS);

        // Notify all clients about the new product
        broadcastMessage("NEW_PRODUCT:" + productName + ":" + clientName + ":" + minimumPrice);
    }

    public synchronized void placeBid(String clientName, String productId, double amount) {
        Product product = products.get(productId);

        if (product == null) {
            ClientHandler client = clients.get(clientName);
            if (client != null) {
                client.sendMessage("ERROR: Product not found.");
            }
            return;
        }

        // Owner cannot bid on their own product
        if (product.getOwner().equals(clientName)) {
            ClientHandler client = clients.get(clientName);
            if (client != null) {
                client.sendMessage("ERROR: Cannot bid on your own product.");
            }
            return;
        }

        // Check if bid is high enough
        if (amount <= product.getCurrentBid()) {
            ClientHandler client = clients.get(clientName);
            if (client != null) {
                client.sendMessage("ERROR: Bid must be higher than current bid: " + product.getCurrentBid());
            }
            return;
        }

        // Update product with new bid
        product.setCurrentBid(amount);
        product.setHighestBidder(clientName);

        // Notify all clients about the new bid
        broadcastMessage("BID_UPDATE:" + product.getName() + ":" + clientName + ":" + amount);
    }

    private void endAuction(String productId) {
        Product product = products.get(productId);

        if (product != null) {
            String winner = product.getHighestBidder();

            if (winner != null) {
                broadcastMessage("AUCTION_END:" + product.getName() + ":" + product.getOwner() + ":" + winner + ":" + product.getCurrentBid());
            } else {
                broadcastMessage("AUCTION_END:" + product.getName() + ":" + product.getOwner() + ":NO_WINNER:0");
            }

            // Remove product from active auctions
            products.remove(productId);
        }
    }

    private void sendProductList(ClientHandler client) {
        StringBuilder productList = new StringBuilder("PRODUCT_LIST:");

        if (products.isEmpty()) {
            productList.append("EMPTY");
        } else {
            for (Product product : products.values()) {
                productList.append(product.getName()).append(",")
                        .append(product.getOwner()).append(",")
                        .append(product.getMinimumPrice()).append(",")
                        .append(product.getCurrentBid()).append(";");
            }
        }

        client.sendMessage(productList.toString());
    }

    public void removeClient(String clientName) {
        clients.remove(clientName);
        System.out.println("Client disconnected: " + clientName);
    }

    public void broadcastMessage(String message) {
        for (ClientHandler client : clients.values()) {
            client.sendMessage(message);
        }
    }

    public static class Product {
        private final String id;
        private final String name;
        private final String owner;
        private final double minimumPrice;
        private double currentBid;
        private String highestBidder;

        public Product(String id, String name, String owner, double minimumPrice) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.minimumPrice = minimumPrice;
            this.currentBid = minimumPrice;
            this.highestBidder = null;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getOwner() { return owner; }
        public double getMinimumPrice() { return minimumPrice; }
        public double getCurrentBid() { return currentBid; }
        public String getHighestBidder() { return highestBidder; }

        public void setCurrentBid(double currentBid) { this.currentBid = currentBid; }
        public void setHighestBidder(String highestBidder) { this.highestBidder = highestBidder; }
    }
}

class ClientHandler implements Runnable {
    private String clientName;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AuctionServer server;

    public ClientHandler(String clientName, Socket socket, AuctionServer server) {
        this.clientName = clientName;
        this.socket = socket;
        this.server = server;

        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            System.err.println("Error creating streams for client " + clientName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                handleClientInput(inputLine);
            }
        } catch (IOException e) {
            System.err.println("Error in client communication with " + clientName + ": " + e.getMessage());
        } finally {
            try {
                server.removeClient(clientName);
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleClientInput(String input) {
        String[] parts = input.split(":", 3);

        if (parts.length < 2) {
            sendMessage("ERROR: Invalid command format");
            return;
        }

        String command = parts[0];

        switch (command) {
            case "SELL":
                if (parts.length < 3) {
                    sendMessage("ERROR: Invalid SELL command format");
                    return;
                }
                String productName = parts[1];
                try {
                    double minimumPrice = Double.parseDouble(parts[2]);
                    server.addProduct(clientName, productName, minimumPrice);
                } catch (NumberFormatException e) {
                    sendMessage("ERROR: Invalid price format");
                }
                break;

            case "BID":
                if (parts.length < 3) {
                    sendMessage("ERROR: Invalid BID command format");
                    return;
                }
                String productId = parts[1];
                try {
                    double bidAmount = Double.parseDouble(parts[2]);
                    server.placeBid(clientName, productId, bidAmount);
                } catch (NumberFormatException e) {
                    sendMessage("ERROR: Invalid bid amount format");
                }
                break;

            default:
                sendMessage("ERROR: Unknown command " + command);
        }
    }

    public void sendMessage(String message) {
        out.println(message);
    }
}