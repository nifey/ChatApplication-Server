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
import java.util.logging.Logger;

public class FileServer implements Runnable {
    private static final Logger logger = Logger.getLogger(FileServer.class.getName());
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
                        } else {
                            socketChannel.close();
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
                        log("Accepted connection from "+client.getRemoteAddress());
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
                    log("Writing file "+ filename + " to "+ socketChannel.getRemoteAddress());
                    FileChannel fc = new FileInputStream(new File(fileDirPath + "/" + filename)).getChannel();
                    ByteBuffer buf = ByteBuffer.allocate(512);
                    int numRead = fc.read(buf);
                    while (numRead != -1) {
                        int bytesRem;
                        buf.flip();
                        do {
                            socketChannel.write(buf);
                            bytesRem = buf.remaining();
                        } while (bytesRem > 0);
                        buf.clear();
                        numRead = fc.read(buf);
                    }
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
        readBuffer.get(bytes);
        String fullRead = new String(bytes);
        log("Read :"+fullRead+" of length "+fullRead.length());
        int in = fullRead.lastIndexOf("##");
        if (in != -1) {
            String read = fullRead.substring(0, in);
            log("Read :"+read+" of length "+read.length());
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
                        log("Generated key string " + keyStr + " for the file of " + numberOfBytes + " bytes");
                        ByteBuffer fileContents = ByteBuffer.wrap(fullRead.substring(in + 2).getBytes());
                        receiveFile(key, numberOfBytes, keyStr, fileContents);
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

    private void receiveFile(SelectionKey key, int numberOfBytes, String keyString, ByteBuffer fileContents){
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            FileChannel fc = new FileOutputStream(new File(fileDirPath+"/"+keyString)).getChannel();
            ByteBuffer buf = ByteBuffer.allocate(512);
            int numRead = 0;
            if(fileContents.remaining() > 0){
                int bytes = fileContents.remaining();
                while(fileContents.remaining()>0){
                    fc.write(fileContents);
                }
                numRead = numRead + bytes;
            }
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
            log("Received file with keyString "+keyString);
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
