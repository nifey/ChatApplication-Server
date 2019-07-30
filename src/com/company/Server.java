package com.company;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.Set;

public class Server{
    ServerSocketChannel serverSocket;
    InetSocketAddress serverAddress;
    HashMap<SelectionKey,String> usernames;
    TreeMap<String, SelectionKey> keyBindings;
    public Server(String hostname, int port) {
        usernames = new HashMap<SelectionKey,String>();
        keyBindings = new TreeMap<String, SelectionKey>();
        serverAddress = new InetSocketAddress("localhost", 12121);
        try {
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(serverAddress);
            serverSocket.configureBlocking(false);
        } catch (IOException e) {
        }
    }
    public void run() throws IOException {
        Selector selector = Selector.open();
        int ops = serverSocket.validOps();
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        while(true){
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iter = keys.iterator();

            while(iter.hasNext()){
                SelectionKey currentKey = iter.next();
                if(currentKey.isAcceptable()){
                    SocketChannel client = serverSocket.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                } else if (currentKey.isReadable()){
                    SocketChannel client = (SocketChannel) currentKey.channel();
                    ByteBuffer buf = ByteBuffer.allocate(200);
                    client.read(buf);
                    String msgText = new String(buf.array()).trim();
                    if(!usernames.containsKey(currentKey)){
                        String[] msgParts = msgText.split("\\$");
                        if(msgParts[0].equals("LOGIN")){
                            if(keyBindings.containsKey(msgParts[1])){
                                ByteBuffer errorMsg = ByteBuffer.wrap(new String("The name is already used. Please use another name.").getBytes());
                                client.write(errorMsg);
                                client.close();
                            } else {
                                usernames.put(currentKey, msgParts[1]);
                                keyBindings.put(msgParts[1], currentKey);
                                ByteBuffer welcomeMsg = ByteBuffer.wrap(new String("Logged in as "+ msgParts[1]).getBytes());
                                client.write(welcomeMsg);
                                System.out.println(msgParts[1] + " Logged in from " + client.getRemoteAddress());
                            }
                        } else {
                            System.out.println(client.getRemoteAddress() + " Failed to login\nMessage received from client: "+msgText);
                            client.close();
                        }
                    } else {
                        handleMessage(currentKey, msgText);
                    }
                }
                iter.remove();
            }
        }
    }

    void handleMessage(SelectionKey key, String msgText) throws IOException{
        if(!msgText.isEmpty()){
            String[] msgParts = msgText.split("\\$");
            if(msgParts[0].equals("LOGOUT")) {
                String username = usernames.get(key);
                usernames.remove(key);
                keyBindings.remove(username);
                ByteBuffer infoMsg = ByteBuffer.wrap(new String("Logged out").getBytes());
                SocketChannel client = (SocketChannel) key.channel();
                client.write(infoMsg);
                System.out.println(username + " logged out" );
                client.close();
            }

            String username = usernames.get(key);
            System.out.println(username + ": " + msgText);
        }
    }
}