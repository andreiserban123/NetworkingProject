import java.io.*;
import java.net.*;
import java.util.*;

public class AuctionClient {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 8888;

    private String clientName;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Map<String, ProductInfo> products = new HashMap<>();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter your name: ");
        String name = scanner.nextLine();

        AuctionClient client = new AuctionClient(name);
        if (client.connect()) {
            client.start();
        }

        scanner.close();
    }

    public AuctionClient(String clientName) {
        this.clientName = clientName;
    }

    public boolean connect() {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send client name to server
            out.println(clientName);

            // Read first server response
            String response = in.readLine();

            if (response.startsWith("ERROR")) {
                System.out.println(response);
                socket.close();
                return false;
            }

            // Process initial product list
            processServerMessage(response);

            return true;

        } catch (IOException e) {
            System.err.println("Error connecting to server: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void start() {
        // Start a thread to read messages from the server
        new Thread(this::receiveMessages).start();

        // Handle user input
        handleUserInput();
    }

    private void receiveMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                processServerMessage(message);
            }
        } catch (IOException e) {
            System.err.println("Error receiving messages: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void processServerMessage(String message) {
        if (message.startsWith("PRODUCT_LIST:")) {
            processProductList(message.substring(13));
        } else if (message.startsWith("NEW_PRODUCT:")) {
            processNewProduct(message.substring(12));
        } else if (message.startsWith("BID_UPDATE:")) {
            processBidUpdate(message.substring(11));
        } else if (message.startsWith("AUCTION_END:")) {
            processAuctionEnd(message.substring(12));
        } else if (message.startsWith("ERROR:")) {
            System.out.println("Server error: " + message.substring(6));
        } else {
            System.out.println("Server message: " + message);
        }
    }

    private void processProductList(String data) {
        products.clear();

        if (data.equals("EMPTY")) {
            System.out.println("No products currently available for bidding.");
            return;
        }

        String[] productEntries = data.split(";");

        System.out.println("\nCurrent products available for bidding:");
        System.out.println("----------------------------------------");

        for (String entry : productEntries) {
            String[] parts = entry.split(",");
            if (parts.length >= 4) {
                String productName = parts[0];
                String owner = parts[1];
                double minimumPrice = Double.parseDouble(parts[2]);
                double currentBid = Double.parseDouble(parts[3]);

                String productId = owner + ":" + productName;
                products.put(productId, new ProductInfo(productName, owner, minimumPrice, currentBid));

                System.out.printf("Product: %s (Owner: %s), Minimum Price: %.2f, Current Bid: %.2f%n",
                        productName, owner, minimumPrice, currentBid);
            }
        }
        System.out.println("----------------------------------------");
    }

    private void processNewProduct(String data) {
        String[] parts = data.split(":");
        if (parts.length >= 3) {
            String productName = parts[0];
            String owner = parts[1];
            double minimumPrice = Double.parseDouble(parts[2]);

            String productId = owner + ":" + productName;
            products.put(productId, new ProductInfo(productName, owner, minimumPrice, minimumPrice));

            System.out.printf("\nNew product added: %s (Owner: %s), Starting Price: %.2f%n",
                    productName, owner, minimumPrice);
        }
    }

    private void processBidUpdate(String data) {
        String[] parts = data.split(":");
        if (parts.length >= 3) {
            String productName = parts[0];
            String bidder = parts[1];
            double amount = Double.parseDouble(parts[2]);

            // Find the product ID
            String productId = null;
            for (Map.Entry<String, ProductInfo> entry : products.entrySet()) {
                if (entry.getValue().getName().equals(productName)) {
                    productId = entry.getKey();
                    break;
                }
            }

            if (productId != null) {
                ProductInfo product = products.get(productId);
                product.setCurrentBid(amount);

                System.out.printf("\nBid update: %s bid %.2f on product %s%n",
                        bidder, amount, productName);
            }
        }
    }

    private void processAuctionEnd(String data) {
        String[] parts = data.split(":");
        if (parts.length >= 4) {
            String productName = parts[0];
            String owner = parts[1];
            String winner = parts[2];
            double finalPrice = Double.parseDouble(parts[3]);

            String productId = owner + ":" + productName;
            products.remove(productId);

            if (winner.equals("NO_WINNER")) {
                System.out.printf("\nAuction ended for product %s (Owner: %s): No winner%n",
                        productName, owner);
            } else {
                System.out.printf("\nAuction ended for product %s (Owner: %s): Winner is %s with bid %.2f%n",
                        productName, owner, winner, finalPrice);
            }
        }
    }

    private void handleUserInput() {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        System.out.println("\nCommands:");
        System.out.println("  sell <product_name> <minimum_price> - Put a product for auction");
        System.out.println("  bid <product_id> <amount> - Bid on a product");
        System.out.println("  list - List all available products");
        System.out.println("  exit - Exit the program");

        while (running) {
            System.out.print("\nEnter command: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit")) {
                running = false;
            } else if (input.equalsIgnoreCase("list")) {
                listProducts();
            } else if (input.startsWith("sell ")) {
                String[] parts = input.substring(5).split(" ", 2);
                if (parts.length == 2) {
                    try {
                        String productName = parts[0];
                        double price = Double.parseDouble(parts[1]);
                        out.println("SELL:" + productName + ":" + price);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid price format. Use decimal number.");
                    }
                } else {
                    System.out.println("Usage: sell <product_name> <minimum_price>");
                }
            } else if (input.startsWith("bid ")) {
                String[] parts = input.substring(4).split(" ", 2);
                if (parts.length == 2) {
                    try {
                        String productId = parts[0];
                        double amount = Double.parseDouble(parts[1]);
                        out.println("BID:" + productId + ":" + amount);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid bid amount format. Use decimal number.");
                    }
                } else {
                    System.out.println("Usage: bid <product_id> <amount>");
                }
            } else {
                System.out.println("Unknown command: " + input);
            }
        }

        close();
        scanner.close();
    }

    private void listProducts() {
        if (products.isEmpty()) {
            System.out.println("No products currently available for bidding.");
            return;
        }

        System.out.println("\nCurrent products available for bidding:");
        System.out.println("----------------------------------------");

        for (Map.Entry<String, ProductInfo> entry : products.entrySet()) {
            String productId = entry.getKey();
            ProductInfo product = entry.getValue();

            System.out.printf("ID: %s, Product: %s (Owner: %s), Minimum Price: %.2f, Current Bid: %.2f%n",
                    productId, product.getName(), product.getOwner(),
                    product.getMinimumPrice(), product.getCurrentBid());
        }

        System.out.println("----------------------------------------");
    }

    private void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("Disconnected from server");
            }
        } catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    private static class ProductInfo {
        private final String name;
        private final String owner;
        private final double minimumPrice;
        private double currentBid;

        public ProductInfo(String name, String owner, double minimumPrice, double currentBid) {
            this.name = name;
            this.owner = owner;
            this.minimumPrice = minimumPrice;
            this.currentBid = currentBid;
        }

        public String getName() { return name; }
        public String getOwner() { return owner; }
        public double getMinimumPrice() { return minimumPrice; }
        public double getCurrentBid() { return currentBid; }

        public void setCurrentBid(double currentBid) { this.currentBid = currentBid; }
    }
}