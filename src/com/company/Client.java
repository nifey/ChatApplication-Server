
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

class ReaderThread extends Thread{
    SocketChannel clientChannel;
    ByteBuffer readBuf;
    private volatile Boolean running;
    public ReaderThread(SocketChannel client){
        running = true;
        clientChannel = client;
        readBuf = ByteBuffer.allocate(200);
    }

    public void run(){
        while(running){
            readBuf.clear();
            try{
                clientChannel.read(readBuf);
            } catch (IOException e){
                e.printStackTrace();
            }
            readBuf.flip();
            byte[] msgBytes =  new byte[readBuf.remaining()];
            readBuf.get(msgBytes);
            String msgText = new String(msgBytes);
            System.out.println("##"+msgText);
            String[] msgParts = msgText.split("\\$");
            switch (msgParts[0]){
                case "MSG":
                    displayMessage(msgParts[1],msgParts[2]);
                    break;
                case "INFO":
                    displayInfo(msgParts[1]);
                    break;
            }
        }
        try {
            clientChannel.close();
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    public void close(){
        running = false;
    }

    void displayMessage(String username, String msg){
        System.out.println(username +  " : " + msg);
    }

    void displayInfo(String info){
        System.out.println(info);
    }
}

class Client {
    public static void main(String[] args) throws IOException {
        Client c = new Client();
        c.run(args[0]);
    }

    public void run(String username) throws IOException{
        InetSocketAddress serverAddress = new InetSocketAddress("localhost",12121);
        SocketChannel client = SocketChannel.open(serverAddress);

        login(client, username);

        ReaderThread rt = new ReaderThread(client);
        rt.start();

        Scanner sc = new Scanner(System.in);
        while(true){
            String str = sc.nextLine().trim();
            if(str.equals("quit")){
                break;
            }
            ByteBuffer buf = ByteBuffer.wrap(str.getBytes());
            client.write(buf);
            buf.clear();
        }

        rt.close();
        logout(client);

    }

    void login(SocketChannel client, String username) throws IOException{
        String message = new String("LOGIN$"+username);
        byte[] messageBytes = message.getBytes();
        ByteBuffer writeBuf = ByteBuffer.wrap(messageBytes);
        client.write(writeBuf);
        writeBuf.clear();
        ByteBuffer readBuf = ByteBuffer.allocate(100);
        client.read(readBuf);
        String msgText = new String (readBuf.array());
        String[] msgParts = msgText.split("\\$");
        if(msgParts[1].equals("FAIL")){
            System.out.println(msgParts[2]);
            System.exit(0);
        }
        System.out.println(new String( readBuf.array()).split("\\$")[1]);
    }

    void logout(SocketChannel client) throws IOException{
        String message = new String("LOGOUT$");
        byte[] messageBytes = message.getBytes();
        ByteBuffer writeBuf = ByteBuffer.wrap(messageBytes);
        client.write(writeBuf);
        writeBuf.clear();
    }
}