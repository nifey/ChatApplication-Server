
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

class Client {
    public static void main(String[] args) throws IOException{
        InetSocketAddress serverAddress = new InetSocketAddress("localhost",12121);
        SocketChannel client = SocketChannel.open(serverAddress);

        login(client, args[0]);

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

        logout(client);

        client.close();
    }

    static void login(SocketChannel client, String username) throws IOException{
        String message = new String("LOGIN$"+username);
        byte[] messageBytes = message.getBytes();
        ByteBuffer writeBuf = ByteBuffer.wrap(messageBytes);
        client.write(writeBuf);
        writeBuf.clear();
        ByteBuffer readBuf = ByteBuffer.allocate(100);
        client.read(readBuf);
        System.out.println(new String( readBuf.array()));
    }

    static void logout(SocketChannel client) throws IOException{
        String message = new String("LOGOUT$");
        byte[] messageBytes = message.getBytes();
        ByteBuffer writeBuf = ByteBuffer.wrap(messageBytes);
        client.write(writeBuf);
        writeBuf.clear();
        ByteBuffer readBuf = ByteBuffer.allocate(100);
        client.read(readBuf);
        System.out.println(new String( readBuf.array()));
    }
}