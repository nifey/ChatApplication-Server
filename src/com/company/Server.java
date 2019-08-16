package com.company;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Server implements Runnable{
    private static Logger logger = null;
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$-7s] %5$s %n");
        logger = Logger.getLogger(Server.class.getName());
    }
    private ServerSocketChannel serverSocket;
    private Selector selector;
    UserDirectory ud;
    GroupDirectory gd;
    private ByteBuffer readBuffer = ByteBuffer.allocate(4092);
    private ArrayList<Task> unprocessedTasks = new ArrayList<Task>();
    private HashMap pendingData = new HashMap();
    private List channelsToWrite = new ArrayList<SocketChannel>();
    private HashMap<String, ArrayList> unsentMessages = new HashMap<String, ArrayList>();

    public Server(String hostname, int port) {
        ud = new UserDirectory();
        gd = new GroupDirectory();
        InetSocketAddress serverAddress = new InetSocketAddress(hostname, port);
        try {
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(serverAddress);
            serverSocket.configureBlocking(false);
            selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            logger.severe(this.getClass() + " : Could not create server socket channel");
            e.printStackTrace();
        }
    }

    private void log(String msg){
        logger.info(this.getClass()+ " : "+ msg);
    }

    public void run(){
        log("Started");
        while(true){
            try {
                synchronized (channelsToWrite){
                    Iterator channels = channelsToWrite.iterator();
                    while(channels.hasNext()){
                        SocketChannel socketChannel = (SocketChannel) channels.next();
                        SelectionKey key = socketChannel.keyFor(selector);
                        if(key!=null && key.isValid()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    }
                    channelsToWrite.clear();
                }

                log("Selector waiting for event");
                this.selector.select();
                Iterator<SelectionKey> iter = this.selector.selectedKeys().iterator();

                while (iter.hasNext()) {
                    SelectionKey currentKey = iter.next();
                    iter.remove();
                    if(!currentKey.isValid()){
                        continue;
                    }
                    if (currentKey.isAcceptable()) {
                        SocketChannel client = serverSocket.accept();
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ);
                        log("Accepted connection from "+ client.getRemoteAddress());
                    } else if (currentKey.isReadable()) {
                        this.read(currentKey);
                    } else if (currentKey.isWritable()){
                        this.write(currentKey);
                    }
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    private void write(SelectionKey key){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (pendingData) {
            List pendingWriteData = (List) this.pendingData.get(socketChannel);
            while (!pendingWriteData.isEmpty()) {
                ByteBuffer buffer = (ByteBuffer) pendingWriteData.get(0);
                try {
                    socketChannel.write(buffer);
                    if(buffer.remaining()>0){
                        break;
                    }
                    pendingWriteData.remove(0);
                } catch (IOException e){
                    logger.severe(this.getClass() + " : Could not write data to socket");
                    e.printStackTrace();
                }
            }

            if(pendingWriteData.isEmpty()){
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    private void read (SelectionKey key){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        readBuffer.clear();
        int numRead;
        try {
            numRead = socketChannel.read(readBuffer);
        } catch (IOException e){
            key.cancel();
            try {
                socketChannel.close();
            } catch (IOException e1){
                e1.printStackTrace();
            }
            return;
        }
        if(numRead == -1){
            try {
                key.channel().close();
            } catch (IOException e){
                e.printStackTrace();
            }
            key.cancel();
            return;
        }

        readBuffer.flip();
        byte[] bytes = new byte[readBuffer.remaining()];
        readBuffer.get(bytes);
        String read = new String(bytes);
        log("Read :"+read+" of length "+read.length());
        synchronized (unprocessedTasks) {
            int in = read.lastIndexOf("##");
            if (in != -1) {
                read = read.substring(0, in);
                for (String taskStr : read.split("##")) {
                    unprocessedTasks.add(new Task(key, taskStr));
                    unprocessedTasks.notify();
                    log("Task Added :" + taskStr);
                }
            }
        }
    }

    public Task getNextTask(){
        synchronized (unprocessedTasks) {
            while(unprocessedTasks.isEmpty()){
                try {
                    unprocessedTasks.wait();
                } catch(InterruptedException e){
                    e.printStackTrace();
                }
            }
            return unprocessedTasks.remove(0);
        }
    }

    void send(String username, String msg){
        if(this.ud.isOnline(username)) {
            SelectionKey key = ud.getKey(username);
            SocketChannel socketChannel = (SocketChannel) key.channel();
            synchronized (channelsToWrite) {
                channelsToWrite.add(socketChannel);
                synchronized (pendingData) {
                    List dataList = (List) pendingData.get(socketChannel);
                    if (dataList == null) {
                        dataList = new ArrayList();
                        pendingData.put(socketChannel, dataList);
                    }
                    dataList.add(ByteBuffer.wrap(msg.getBytes()));
                }
            }
            selector.wakeup();
        } else {
            synchronized (unsentMessages) {
                ArrayList<String> messages = unsentMessages.get(username);
                if (messages == null) {
                    messages = new ArrayList<String>();
                    unsentMessages.put(username, messages);
                }
                messages.add(msg);
                log("Added message: "+ msg + " to unsent messages as "+username + " is not online");
            }
        }
    }

    void sendUnsentMessages(String username){
        if(this.ud.isOnline(username)){
            SelectionKey key = this.ud.getKey(username);
            SocketChannel socketChannel = (SocketChannel) key.channel();
            synchronized (unsentMessages){
               if(unsentMessages.containsKey(username)) {
                   ArrayList<String> messages = unsentMessages.get(username);
                   if(messages.size()==0){
                       return;
                   }
                   log("Sending unsent messages to "+ username);
                   synchronized (channelsToWrite) {
                       channelsToWrite.add(socketChannel);
                       synchronized (pendingData) {
                           List dataList = (List) pendingData.get(socketChannel);
                           if (dataList == null) {
                               dataList = new ArrayList();
                               pendingData.put(socketChannel, dataList);
                           }
                           for (String message : messages) {
                               dataList.add(ByteBuffer.wrap(message.getBytes()));
                           }
                           unsentMessages.remove(username);
                       }
                   }
                   selector.wakeup();
               }
           }
        }
    }

    boolean hasUnsentMessages(String username){
        synchronized (unsentMessages) {
            return unsentMessages.containsKey(username);
        }
    }

    void sendKey(SelectionKey key, String msg){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (channelsToWrite) {
            channelsToWrite.add(socketChannel);
            synchronized (pendingData) {
                List dataList = (List) pendingData.get(socketChannel);
                if (dataList == null) {
                    dataList = new ArrayList();
                    pendingData.put(socketChannel, dataList);
                }
                dataList.add(ByteBuffer.wrap(msg.getBytes()));
            }
        }
        selector.wakeup();
    }

}