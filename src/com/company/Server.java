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
            e.printStackTrace();
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
                                sendInfo(currentKey,"FAIL$The name is already used. Please use another name.");
                                client.close();
                            } else {
                                usernames.put(currentKey, msgParts[1]);
                                keyBindings.put(msgParts[1], currentKey);
                                sendInfo(currentKey,"Logged in as "+ msgParts[1]);
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
            switch (msgParts[0]) {
                case "LOGOUT":
                    String username = usernames.get(key);
                    usernames.remove(key);
                    keyBindings.remove(username);
                    sendInfo(key, "Logged out");
                    SocketChannel client = (SocketChannel) key.channel();
                    System.out.println(username + " logged out");
                    client.close();
                    break;
                case "MSG":
                    String sender = usernames.get(key);
                    String receiver = msgParts[1];
                    SelectionKey receiverKey = keyBindings.get(receiver);
                    if(receiverKey == null){
                        sendInfo(key, receiver + " is not online");
                    } else {
                        sendMsg(sender, receiverKey, msgParts[2]);
                    }
                    System.out.println(sender + " -> " + receiver + ": " + msgText);
                    break;
            }
        }
    }

    void sendInfo(SelectionKey key, String info){
        SocketChannel client = (SocketChannel) key.channel();
        if(client.isOpen()) {
            String infoText = "INFO$" + info;
            ByteBuffer infoMsg = ByteBuffer.wrap(infoText.getBytes());
            try {
                client.write(infoMsg);
            } catch (IOException e) {
                e.printStackTrace();
            }
            infoMsg.clear();
        }
    }

    void sendMsg(String sender, SelectionKey receiverKey, String msg){
        SocketChannel client = (SocketChannel) receiverKey.channel();
        if(client.isOpen()) {
            String msgText = "MSG$" + sender + "$" + msg;
            ByteBuffer msgBuf = ByteBuffer.wrap(msgText.getBytes());
            try {
                client.write(msgBuf);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}