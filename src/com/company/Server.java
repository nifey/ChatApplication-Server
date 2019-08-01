package com.company;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

public class Server{
    private ServerSocketChannel serverSocket;
    HashMap<String, ArrayList<SelectionKey>> groups;
    HashMap<SelectionKey, ArrayList<String>> userGroups;
    private UserDirectory ud;

    public Server(String hostname, int port) {
        ud = new UserDirectory();
        groups = new HashMap<String, ArrayList<SelectionKey>>();
        userGroups = new HashMap<SelectionKey, ArrayList<String>>();
        InetSocketAddress serverAddress = new InetSocketAddress("localhost", 12121);
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
                    buf.flip();
                    String msgText = new String(buf.array()).trim();
                    if(!ud.keyPresent(currentKey)){
                        String[] msgParts = msgText.split("\\$");
                        if(msgParts[0].equals("LOGIN")){
                            if(ud.namePresent(msgParts[1])){
                                sendInfo(currentKey,"FAIL$The name is already used. Please use another name.");
                                client.close();
                            } else {
                                ud.put(msgParts[1], currentKey);
                                sendInfo(currentKey,"Logged in as "+ msgParts[1]);
                                log(msgParts[1] + " Logged in from " + client.getRemoteAddress());
                            }
                        } else {
                            log(client.getRemoteAddress() + " Failed to login\nMessage received from client: "+msgText);
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
            String sender = ud.getName(key);
            switch (msgParts[0]) {
                case "LOGOUT":
                    ud.deleteKey(key);
                    if(userGroups.get(key) != null) {
                        for (String grp : userGroups.get(key)) {
                            ArrayList grpMembers = groups.get(grp);
                            grpMembers.remove(key);
                            if (grpMembers.size() == 0) {
                                groups.remove(grp);
                            } else {
                                groups.put(grp, grpMembers);
                            }
                        }
                    }

                    sendInfo(key, "Logged out");
                    SocketChannel client = (SocketChannel) key.channel();
                    log(sender + " logged out");
                    client.close();
                    break;
                case "MSG":
                    if(msgParts.length>0) {
                        String receiver = msgParts[1];
                        SelectionKey receiverKey = ud.getKey(receiver);
                        if (receiverKey == null) {
                            sendInfo(key, receiver + " is not online");
                        } else {
                            if (msgParts.length > 2) {
                                sendMsg(sender, receiverKey, msgParts[2]);
                                log(sender + " -> " + receiver + ": " + msgText);
                            }
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GCREATE":
                    //Creates a group and adds the sender in the group
                    if(msgParts.length>1){
                        String grpName = msgParts[1];
                        if(groups.containsKey(grpName)){
                            sendInfo(key,"Group name already in use. Please select a different name");
                        } else {
                            ArrayList<SelectionKey> memberKeys = new ArrayList<SelectionKey>();
                            memberKeys.add(key);
                            groups.put(grpName, memberKeys);
                            if(userGroups.containsKey(key)) {
                                ArrayList<String> grps = userGroups.get(key);
                                grps.add(grpName);
                                userGroups.put(key, grps);
                            } else {
                                ArrayList<String> grps = new ArrayList<String>();
                                grps.add(grpName);
                                userGroups.put(key, grps);
                            }
                            sendInfo(key, "Group "+grpName+" has been created");
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GADD":
                    //Checks if the sender is admin and the user is online and adds in the group
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        if(grpName.isEmpty() || !groups.containsKey(grpName)){
                            sendInfo(key, "Group " + grpName + " not found");
                        } else if(!key.equals(getGroupAdminKey(grpName))){
                            sendInfo(key, "Only Group Admin can add users");
                        }else {
                            String members = msgParts[2];
                            for (String member : members.split(",")) {
                                if (!ud.namePresent(member)) {
                                    sendInfo(key, "User " + member + " is not online");
                                } else {
                                    SelectionKey memberKey = ud.getKey(member);
                                    ArrayList grpMembers = groups.get(grpName);
                                    if (!grpMembers.contains(memberKey)) {
                                        grpMembers.add(memberKey);
                                        groups.put(grpName, grpMembers);

                                        ArrayList userGrps = userGroups.get(key);
                                        userGrps.add(grpName);
                                        userGroups.put(key, userGrps);
                                        sendInfo(key, "User " + member + " is added to the group " + grpName);
                                    } else {
                                        sendInfo(key, "User " + member + " is already in the group " + grpName);
                                    }
                                }
                            }

                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GREMOVE":
                    //Checks if the user is admin or if the user wants to remove himself from a group and removes the user
                    //also delete the group if no one is in the group
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        String users = msgParts[2];
                        if(!groups.containsKey(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(key.equals(getGroupAdminKey(grpName)) || (users.split(",").length==1 && users.split(",")[0].equals(ud.getName(key)))){
                            ArrayList<SelectionKey> grpMembers = groups.get(grpName);
                            for(String user: users.split(",")){
                                grpMembers.remove(user);
                                SelectionKey userKey = ud.getKey(user);
                                ArrayList<String> userGrps = userGroups.get(userKey);
                                userGrps.remove(grpName);
                                userGroups.put(userKey, userGrps);
                                sendInfo(key, "User "+ user + " has been removed");
                            }
                            if(grpMembers.size()==0){
                                groups.remove(grpName);
                                System.out.println("AAA");
                            } else {
                                groups.put(grpName, grpMembers);
                                System.out.println("BBB");
                            }
                        } else {
                            sendInfo(key, "You are not permitted to remove users from this group");
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GDELETE":
                    //Checks if user is admin and deletes the group
                    if(msgParts.length>1){
                        String grpName = msgParts[1];
                        if(!groups.containsKey(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(key.equals(getGroupAdminKey(grpName))){
                            ArrayList<SelectionKey> grpMembers = groups.get(grpName);
                            for(SelectionKey memberKey: grpMembers){
                                ArrayList userGrps = userGroups.get(memberKey);
                                userGrps.remove(grpName);
                                userGroups.put(memberKey, userGrps);
                            }
                            groups.remove(grpName);
                        } else {
                            sendInfo(key, "You are not permitted to delete this group");
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GMSG":
                    //Check if the group exists and also if the sender is a part of the group and Sends a group message
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        String msg = msgParts[2];
                        if(!groups.containsKey(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if (userGroups.get(key)!=null && userGroups.get(key).contains(grpName)) {
                            //TODO
                            sendGrpMsg(sender, grpName, msg);
                        } else {
                            sendInfo(key, "You are not a member of the group "+ grpName);
                        }
                        log(sender + " => " + grpName + ": " + msgText);
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
            }
        }
    }

    SelectionKey getGroupAdminKey(String grpName){
        return groups.get(grpName).get(0);
    }

    void sendInfo(SelectionKey key, String info){
        SocketChannel client = (SocketChannel) key.channel();
        if(client.isOpen()) {
            String infoText = "INFO$" + info;
            ByteBuffer infoBuf = ByteBuffer.wrap(infoText.getBytes());
            try {
                client.write(infoBuf);
            } catch (IOException e) {
                e.printStackTrace();
            }
            infoBuf.clear();
        }
    }

    void sendGrpMsg(String sender, String grpName, String msg){
        for(SelectionKey memberKey: groups.get(grpName)){
            SocketChannel client = (SocketChannel) memberKey.channel();
            if(client.isOpen()) {
                String msgText = "GMSG$" + grpName + "$" + sender + "$" + msg;
                ByteBuffer msgBuf = ByteBuffer.wrap(msgText.getBytes());
                try {
                    client.write(msgBuf);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                msgBuf.clear();
            }
            //handle if the user went offline after joining a group
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
            msgBuf.clear();
        }
    }

    void log(String log){
        System.out.println(log);
    }
}