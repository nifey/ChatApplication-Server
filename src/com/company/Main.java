package com.company;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        Server server = new Server("localhost", 12121);
        new Thread(server).start();

        Worker w1 = new Worker(server);
        Worker w2 = new Worker(server);
        new Thread(w1).start();
        new Thread(w2).start();

        FileServer fs = new FileServer("localhost", 12345);
        new Thread(fs).start();
    }
}
