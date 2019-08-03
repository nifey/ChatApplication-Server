package com.company;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        Server server = new Server("localhost", 12121);
        Worker w1 = new Worker(server);
        Worker w2 = new Worker(server);
        new Thread(server).start();
        new Thread(w1).start();
        new Thread(w2).start();
    }
}
