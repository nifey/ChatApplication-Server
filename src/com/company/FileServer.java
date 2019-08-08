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
    private HashMap pendingFile = new HashMap();
    private HashMap pendingMessages = new HashMap();
    private List channelsToWrite = new ArrayList<SocketChannel>();
    private List fileList = new ArrayList<String>();
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
                        if(key!=null && key.isValid()) {
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
        synchronized (pendingFile) {
            filename = (String) this.pendingFile.get(socketChannel);
            if(filename != null) {
                this.pendingFile.remove(socketChannel);
            }
        }
        if(filename != null) {
            Path path = Paths.get(fileDirPath + "/" + filename);
            if (Files.exists(path, new LinkOption[]{LinkOption.NOFOLLOW_LINKS})) {
                try {
                    System.out.println("Writing file");
                    FileChannel fc = new FileInputStream(new File(fileDirPath + "/" + filename)).getChannel();
                    ByteBuffer buf = ByteBuffer.allocate(512);
                    int numRead = fc.read(buf);
                    while (numRead != -1) {
                        int bytesRem;
                        buf.flip();
                        do {
                            System.out.println("in");
                            socketChannel.write(buf);
                            bytesRem = buf.remaining();
                        } while (bytesRem > 0);
                        buf.clear();
                        numRead = fc.read(buf);
                        System.out.println("writing...");
                    }
                    System.out.println("finished writing...");
                    socketChannel.close();
                    fc.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            synchronized (pendingMessages){
                try {
                    List msgList = (List) pendingMessages.get(socketChannel);
                    while (!msgList.isEmpty()) {
                        String msg = (String)msgList.get(0);
                        msgList.remove(0);
                        ByteBuffer buf = ByteBuffer.wrap(msg.getBytes());
                        while (buf.remaining() > 0) {
                            socketChannel.write(buf);
                        }
                    }
                    socketChannel.close();
                }catch (Exception e){
                    e.printStackTrace();
                }
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
                if(msgParts.length>1) {
                    int numberOfBytes = -1;
                    try {
                        numberOfBytes = Integer.parseInt(msgParts[1]);
                    }catch (NumberFormatException e){
                        e.printStackTrace();
                    }
                    if(numberOfBytes>0) {
                        String keyStr = generateString(10);
                        while (fileList.contains(keyStr)) {
                            keyStr = generateString(10);
                        }
                        System.out.println("Generated key string " + keyStr + " for the file of " + numberOfBytes + " bytes");
                        receiveFile(key, numberOfBytes, keyStr);
                    }
                }
            } else if (msgParts[0].equals("SEND")){
                if(msgParts.length>1){
                    if(fileList.contains(msgParts[1])) {
                        sendFile(key, msgParts[1]);
                    }
                }
            }
        }
    }

    private void receiveFile(SelectionKey key, int numberOfBytes, String keyString){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            FileChannel fc = new FileOutputStream(new File(fileDirPath+"/"+keyString)).getChannel();
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
            fc.close();
            fileList.add(keyString);
            System.out.println("Received file with keyString"+keyString);
            sendMessage(key, "RECEIVED$"+keyString+"$"+ numberOfBytes+"##");
        }catch (Exception e ){
            e.printStackTrace();
        }
    }

    void sendFile(SelectionKey key, String keyString){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (channelsToWrite) {
            if(!channelsToWrite.contains(socketChannel)) {
                channelsToWrite.add(socketChannel);
            }
            synchronized (pendingFile) {
                pendingFile.put(socketChannel, keyString);
                System.out.println("DEBUG: Server: Filename added to pending data");
            }
        }
        selector.wakeup();
    }

    void sendMessage(SelectionKey key, String msg){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (channelsToWrite) {
            if(!channelsToWrite.contains(socketChannel)){
                channelsToWrite.add(socketChannel);
            }
            synchronized (pendingMessages){
                List messageList = (List)pendingMessages.get(socketChannel);
                if(messageList == null){
                    messageList = new ArrayList<String>();
                    pendingMessages.put(socketChannel, messageList);
                }
                messageList.add(msg);
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
