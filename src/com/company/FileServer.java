package com.company;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class FileServer implements Runnable {
    private ServerSocketChannel serverSocket;
    private Selector selector;
    private ByteBuffer readBuffer = ByteBuffer.allocate(4092);
    private HashMap pendingData = new HashMap();
    private List channelsToWrite = new ArrayList<SocketChannel>();
    private HashMap<String, String> fileMap = new HashMap<String, String>();
    private String fileDirPath = "/home/nihaal/filedir/";

    public FileServer(String hostname, int port){
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
        System.out.println("DEBUG: FileServer: Started");
        while(true){
            try {
                synchronized (channelsToWrite){
                    Iterator channels = channelsToWrite.iterator();
                    while(channels.hasNext()){
                        SocketChannel socketChannel = (SocketChannel) channels.next();
                        SelectionKey key = socketChannel.keyFor(selector);
                        if(key!=null) {
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else {
                            socketChannel.close();
                        }
                    }
                    channelsToWrite.clear();
                }

                System.out.println("DEBUG: FileServer: Selector waiting for event");
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
                        System.out.println("DEBUG: FileServer: Accepted connection");
                    } else if (currentKey.isReadable()) {
                        this.read(currentKey);
                        System.out.println("DEBUG: FileServer: Finished reading data");
                    } else if (currentKey.isWritable()){
                        this.write(currentKey);
                        System.out.println("DEBUG: FileServer: Finished writing data");
                    }
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    private void write(SelectionKey key){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        String filename;
        synchronized (pendingData) {
            filename = (String) this.pendingData.get(socketChannel);
            this.pendingData.remove(socketChannel);
        }
        Path path = Paths.get(fileDirPath +"/"+filename);
        if(Files.exists(path,new LinkOption[]{ LinkOption.NOFOLLOW_LINKS})) {
            try {
                System.out.println("Writing file");
                FileChannel fc = new FileInputStream(new File(fileDirPath+ "/"+filename)).getChannel();
                ByteBuffer buf = ByteBuffer.allocate(512);
                int numRead = fc.read(buf);
                while(numRead != -1){
                    int bytesRem;
                    buf.flip();
                    do {
                        System.out.println("in");
                        socketChannel.write(buf);
                        bytesRem = buf.remaining();
                    }while (bytesRem>0);
                    buf.clear();
                    numRead = fc.read(buf);
                    System.out.println("writing...");
                }
                System.out.println("finished writing...");
                socketChannel.close();
                fc.close();
            }catch (Exception e ){
                e.printStackTrace();
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
        int in = read.lastIndexOf("##");
        if (in != -1) {
            read = read.substring(0, in);
            System.out.println("DEBUG: Server: readinside " + read);
            String [] msgParts = read.split("\\$");
            if(msgParts[0].equals("RECEIVE")){
                if(msgParts.length>2) {
                    String filename = msgParts[1];
                    int numberOfBytes = Integer.parseInt(msgParts[2]);
                    String keyStr =  generateString(10);
                    while(fileMap.containsKey(keyStr)){
                        keyStr = generateString(10);
                    }
                    System.out.println("Generated key string "+keyStr+" for the file "+filename+" of "+numberOfBytes + " bytes");
                    receiveFile(key, filename, numberOfBytes, keyStr);
                }
            } else if (msgParts[0].equals("SEND")){
                if(msgParts.length>1 && fileMap.containsKey(msgParts[1])){
                    sendFile(key, msgParts[1]);
                }
            }
        }
    }

    private void receiveFile(SelectionKey key, String filename, int numberOfBytes, String keyString){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            FileChannel fc = new FileOutputStream(new File(fileDirPath+"/"+filename)).getChannel();
            ByteBuffer buf = ByteBuffer.allocate(512);
            int numRead = 0;
            while(numRead < numberOfBytes){
                int bytes = socketChannel.read(buf);
                buf.flip();
                while(buf.remaining()>0) {
                    fc.write(buf);
                }
                buf.clear();
                numRead = numRead + bytes;
            }
            socketChannel.close();
            fc.close();
            fileMap.put(keyString, filename);
            System.out.println("Received file "+filename);
        }catch (Exception e ){
            e.printStackTrace();
        }
    }

    void sendFile(SelectionKey key, String keyString){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (channelsToWrite) {
            channelsToWrite.add(socketChannel);
            synchronized (pendingData) {
                pendingData.put(socketChannel, fileMap.get(keyString));
                System.out.println("DEBUG: Server: Filename added to pending data");
            }
        }
        selector.wakeup();
    }

    public String generateString(int length) {
        Random random = new Random();
        String abc = "abcdefghijklmnopqrstuvwxyz";
        String characters = "0123456789" + abc + abc.toUpperCase() + "{}[]()";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

}
