package com.company;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

public class Server implements Runnable{
    private ServerSocketChannel serverSocket;
    private Selector selector;
    UserDirectory ud;
    GroupDirectory gd;
    private ByteBuffer readBuffer = ByteBuffer.allocate(4092);
    private ArrayList<Task> unprocessedTasks = new ArrayList<Task>();
    private HashMap pendingData = new HashMap();
    private List channelsToWrite = new ArrayList<SocketChannel>();

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
            e.printStackTrace();
        }
    }

    public void run(){
        System.out.println("DEBUG: Server: Started");
        while(true){
            try {
                synchronized (channelsToWrite){
                    Iterator channels = channelsToWrite.iterator();
                    while(channels.hasNext()){
                        SocketChannel socketChannel = (SocketChannel) channels.next();
                        SelectionKey key = socketChannel.keyFor(selector);
                        if(key!=null && key.isValid()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else {
                            ud.deleteKey(key);
                            gd.removeByKey(key);
                            socketChannel.close();
                        }
                    }
                    channelsToWrite.clear();
                }

                System.out.println("DEBUG: Server: Selector waiting for event");
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
                        System.out.println("DEBUG: Server: Accepted connection");
                    } else if (currentKey.isReadable()) {
                        this.read(currentKey);
                        System.out.println("DEBUG: Server: Finished reading data");
                    } else if (currentKey.isWritable()){
                        this.write(currentKey);
                        System.out.println("DEBUG: Server: Finished writing data");
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
                System.out.println("DEBUG: Server: Found something to write");
                ByteBuffer buffer = (ByteBuffer) pendingWriteData.get(0);
                try {
                    socketChannel.write(buffer);
                    System.out.println("DEBUG: Server: Written Data");
                    if(buffer.remaining()>0){
                        break;
                    }
                    pendingWriteData.remove(0);
                } catch (IOException e){
                    e.printStackTrace();
                }
            }

            if(pendingWriteData.isEmpty()){
                key.interestOps(SelectionKey.OP_READ);
                System.out.println("DEBUG: Server: Nothing more to write in that channel");
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
        System.out.println("DEBUG: Server: Read "+readBuffer.remaining()+" Characters");
        readBuffer.get(bytes);
        String read = new String(bytes);
        System.out.println("DEBUG: Server: Read :"+read+" of length "+read.length());
        synchronized (unprocessedTasks) {
            int in = read.lastIndexOf("##");
            if (in != -1) {
                read = read.substring(0, in);
                System.out.println("DEBUG: Server: readinside " + read);
                for (String taskStr : read.split("##")) {
                    System.out.println("DEBUG: Server: taskstr: " + taskStr);
                    unprocessedTasks.add(new Task(key, taskStr));
                    unprocessedTasks.notify();
                    System.out.println("DEBUG: Server: Task Added " + taskStr);
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
            System.out.println("DEBUG: Server: Got a task");
            return unprocessedTasks.remove(0);
        }
    }

    void send(SelectionKey key, String msg){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (channelsToWrite) {
            channelsToWrite.add(socketChannel);
            synchronized (pendingData) {
                List dataList = (List) pendingData.get(socketChannel);
                if(dataList == null){
                    dataList = new ArrayList();
                    pendingData.put(socketChannel, dataList);
                }
                dataList.add(ByteBuffer.wrap(msg.getBytes()));
                System.out.println("DEBUG: Server: Data added to pending data");
            }
        }
        selector.wakeup();
    }

}