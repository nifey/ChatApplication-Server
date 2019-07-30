
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

class Client {
    public static void main(String[] args) throws IOException, InterruptedException{
        InetSocketAddress serverAddress = new InetSocketAddress("localhost",12121);
        SocketChannel client = SocketChannel.open(serverAddress);
        String message = new String("Close");
        byte[] messageBytes = message.getBytes();
        ByteBuffer buf = ByteBuffer.wrap(messageBytes);
        System.out.println("Sending message");
        client.write(buf);
        buf.clear();
        client.close();
    }
}