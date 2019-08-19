
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.CyclicBarrier;

public class Analyser {

    public static void main(String[] args) {
        if(args.length<2){
            System.out.println("enter no of threads and no of messages");
            System.exit(0);
        }
        int numThreads = Integer.parseInt(args[0]);
        int numMessages = Integer.parseInt(args[1]);
        final CyclicBarrier gate = new CyclicBarrier(numThreads+1);
        final CyclicBarrier endGate = new CyclicBarrier(numThreads+1);
        for (int count = 0; count < numThreads; count++) {
            new Thread(new EchoClient("localhost",12121, count, gate, endGate,numMessages)).start();
        }
        try{
            gate.await();
        } catch (Exception e){
            e.printStackTrace();
        }

        try{
            endGate.await();
        } catch (Exception e){
            e.printStackTrace();
        }

        double sum = 0;
        for(Object item: EchoClient.latList){
            sum += (double) item;
        }
        double avg = (double)sum/((double)numMessages * (double)numThreads);
        System.out.println("Global average latency: " + avg);
    }

    static class EchoClient implements Runnable {
        static ArrayList<Object> latList = new ArrayList<>();
        SocketChannel socketChannel;
        private Selector selector;
        private ByteBuffer readBuffer = ByteBuffer.allocate(4092);
        private ArrayList<String> pendingWriteData = new ArrayList<>();
        private HashMap<String, Object> stringMap;
        private ArrayList<Object> latencies = new ArrayList<>();
        String username;
        CyclicBarrier gate, endGate;
        int threadNumber;
        private Boolean firstWrite = true;
        private Boolean gotMsg = false;
        private Boolean end = false;
        private int numMessages;
        private int gotMessages;

        public EchoClient(String hostname, int port, int threadNumber, CyclicBarrier gate, CyclicBarrier endGate, int numMessages) {
            this.gate = gate;
            this.endGate = endGate;
            this.threadNumber = threadNumber;
            this.username = generateRandomString(10);
            this.stringMap =  new HashMap<String, Object>();
            this.numMessages = numMessages;
            this.gotMessages = 0;
            InetSocketAddress serverAddress = new InetSocketAddress(hostname, port);
            try {
                this.socketChannel = SocketChannel.open(serverAddress);
                this.socketChannel.configureBlocking(false);
                selector = Selector.open();
                socketChannel.register(selector, SelectionKey.OP_READ);
            } catch (ConnectException e) {
                System.out.println("Could not connect to server");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private String generateRandomString(int n){
            String AlphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789" + "abcdefghijklmnopqrstuvxyz";
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                int index = (int)(AlphaNumericString.length() * Math.random());
                sb.append(AlphaNumericString.charAt(index));
            }
            return sb.toString();
        }

        public void run() {

            // Generate fake data
            send("LOGIN$"+this.username+"##");
            for (int i = 0; i < numMessages; i++) {
                String str = generateRandomString(20);
                send("MSG$" + this.username + "$" + str + "##");
            }

            try {
                this.gate.await();
            } catch (Exception e){
                e.printStackTrace();
            }

            SelectionKey key = socketChannel.keyFor(selector);
            key.interestOps(SelectionKey.OP_WRITE);
            while (true) {
                try {
                    this.selector.select();
                    Iterator<SelectionKey> iter = this.selector.selectedKeys().iterator();

                    while (iter.hasNext()) {
                        SelectionKey currentKey = iter.next();
                        iter.remove();
                        if (!currentKey.isValid()) {
                            continue;
                        }
                        if (currentKey.isAcceptable()) {
                            socketChannel.finishConnect();
                        } else if (currentKey.isReadable()) {
                            this.read(currentKey);
                        } else if (currentKey.isWritable()) {
                            this.write(currentKey);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if(end){
                    break;
                }
            }

            latencies.remove(0);
            for(Object latency: latencies){
                addToList((double)latency);
            }

            try{
                endGate.await();
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        private void addToList(double latency){
            latList.add(latency);
        }

        private void send(String msg) {
            synchronized (pendingWriteData) {
                pendingWriteData.add(msg);
            }
        }

        private void write(SelectionKey key) {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            synchronized (pendingWriteData) {
                if (!pendingWriteData.isEmpty()) {
                    String str = pendingWriteData.get(0);
                    ByteBuffer buffer = ByteBuffer.wrap(str.getBytes());
                    try {
                        do{
                            socketChannel.write(buffer);
                        }while(buffer.remaining()>0);
                        pendingWriteData.remove(0);
                        if(str.startsWith("MSG$")) {
                            String keyStr = str.split("\\$")[2];
                            keyStr = keyStr.substring(0, keyStr.lastIndexOf("##"));
                            stringMap.put(keyStr, System.currentTimeMillis());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if(!firstWrite) {
                        key.interestOps(SelectionKey.OP_READ);
                    } else {
                        firstWrite = false;
                    }
                    gotMsg = false;
                }
            }
        }

        private void read(SelectionKey key) {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            readBuffer.clear();
            int numRead;
            try {
                numRead = socketChannel.read(readBuffer);
            } catch (IOException e) {
                key.cancel();
                try {
                    socketChannel.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return;
            }
            if (numRead == -1) {
                try {
                    key.channel().close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                key.cancel();
                return;
            }

            readBuffer.flip();
            byte[] bytes = new byte[readBuffer.remaining()];
            readBuffer.get(bytes);
            String read = new String(bytes);
            int in = read.lastIndexOf("##");
            if (in != -1) {
                read = read.substring(0, in);
                for (String msgText : read.split("##")) {
                    String[] msgParts = msgText.split("\\$");
                    if (msgParts.length > 2 && msgParts[0].equals("MSG") && msgParts[1].equals(username)) {
                        long sent = (long) stringMap.get(msgParts[2]);
                        stringMap.remove(msgParts[2]);
                        long received = System.currentTimeMillis();
                        double duration = (received - sent);
                        latencies.add(duration);
                        gotMsg = true;
                        gotMessages++;
                    }
                }
            }
            if(gotMessages==numMessages) {
                end = true;
            } else if(gotMsg){
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }
}