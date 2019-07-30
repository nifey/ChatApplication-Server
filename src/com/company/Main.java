package com.company;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class Main {

    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        InetSocketAddress serverAddress = new InetSocketAddress("localhost",12121);
        serverSocket.bind(serverAddress);
        serverSocket.configureBlocking(false);

        Selector selector = Selector.open();

        int ops = serverSocket.validOps();
        SelectionKey key = serverSocket.register(selector, ops);

        while(true) {
            selector.select();

            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iter = keys.iterator();

            while(iter.hasNext()){
                SelectionKey currentKey = iter.next();
                if (currentKey.isAcceptable()){
                    SocketChannel client = serverSocket.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                    System.out.println("Created connection with "+ client.getRemoteAddress());
                } else if (currentKey.isReadable()){
                    SocketChannel client = (SocketChannel) currentKey.channel();
                    ByteBuffer buf = ByteBuffer.allocate(200);
                    client.read(buf);
                    String result = new String(buf.array()).trim();

                    System.out.println("Received from "+client.getRemoteAddress()+" : "+result);
                    if(result.equals("Close")){
                        client.close();
                    }
                }
                iter.remove();
            }
        }
    }
}
