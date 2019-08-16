package com.company;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.logging.Logger;

public class Worker implements Runnable {
    private static final Logger logger = Logger.getLogger(Worker.class.getName());

    private Server server;
    public Worker(Server server){
        this.server = server;
    }

    public void run(){
        log("Started");
        while(true) {
            Task t = this.server.getNextTask();
            log("Got a new task: "+ t.getMsg());
            try {
                handleMessage(t.getKey(), t.getMsg());
            } catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    private void log(String msg){
        logger.info(this.getClass()+ " : "+msg);
    }

    private void handleMessage(SelectionKey key, String msgText) throws IOException {
        if(!msgText.isEmpty()){
            String[] msgParts = msgText.split("\\$");
            SocketChannel client = (SocketChannel) key.channel();
            if(!server.ud.isOnline(key)){
                if(msgParts[0].equals("LOGIN")){
                    if (server.ud.namePresent(msgParts[1])) {
                        sendInfo(key, "The name is already used. Please use another name.");
                    } else {
                        server.ud.put(msgParts[1], key);
                        broadcastUserList();
                        sendInfo(key, "Logged in as " + msgParts[1]);
                        log(msgParts[1] + " Logged in from " + client.getRemoteAddress());
                        if(server.hasUnsentMessages(msgParts[1])){
                            server.sendUnsentMessages(msgParts[1]);
                        }
                        if(server.gd.usernameExists(msgParts[1])){
                            sendGROUPS(key, server.gd.getGroups(msgParts[1]));
                        }
                    }
                } else {
                    log(client.getRemoteAddress() + " Failed to login\nMessage received from client: " + msgText);
                }
                return;
            }
            String sender = server.ud.getName(key);
            switch (msgParts[0]) {
                case "LOGOUT":
                    server.ud.deleteKey(key);
                    broadcastUserList();

                    log(sender + " logged out");
                    break;
                case "MSG":
                    if(msgParts.length>2) {
                        String receiver = msgParts[1];
                        SelectionKey receiverKey = server.ud.getKey(receiver);
                        if (receiverKey == null) {
                            sendInfo(key, "The username "+msgParts[1]+" is not registered");
                        } else {
                            sendMsg(sender, receiverKey, msgParts[2]);
                            if(receiverKey!=key) {
                                sendSelfMsg(receiver, sender, key, msgParts[2]);
                            }
                            log(sender + " -> " + receiver + ": " + msgParts[2]);
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GCREATE":
                    //Creates a group and adds the sender in the group
                    if(msgParts.length>1){
                        String grpName = msgParts[1];
                        if(server.gd.groupExists(grpName)){
                            sendInfo(key,"Group name already in use. Please select a different name");
                        } else {
                            server.gd.put(server.ud.getName(key), grpName);
                            sendGROUPS(key, server.gd.getGroups(server.ud.getName(key)));
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
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(key, "Group " + grpName + " not found");
                        } else if(!key.equals(server.ud.getKey(server.gd.getGroupAdminUsername(grpName)))){
                            sendInfo(key, "Only Group Admin can add users");
                        }else {
                            String members = msgParts[2];
                            for (String member : members.split(",")) {
                                if (!server.ud.namePresent(member)) {
                                    sendInfo(key, "User " + member + " is not online");
                                } else {
                                    SelectionKey memberKey = server.ud.getKey(member);
                                    if (!server.gd.isInGroup(member, grpName)) {
                                        server.gd.put(member, grpName);
                                        sendInfo(key, "User " + member + " is added to the group " + grpName);
                                        sendInfo(memberKey, "You have been added to the group " + grpName);
                                        sendGROUPS(memberKey, server.gd.getGroups(member));
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
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(key.equals(server.ud.getKey(server.gd.getGroupAdminUsername(grpName))) || (users.split(",").length==1 && users.split(",")[0].equals(server.ud.getName(key)))){
                            for(String user: users.split(",")){
                                SelectionKey userKey = server.ud.getKey(user);
                                server.gd.removeFromGroup(user, grpName);
                                sendInfo(key, "User "+ user + " has been removed");
                                sendInfo(userKey, "You have been removed from the group "+ grpName);
                                sendGROUPS(userKey,server.gd.getGroups(user));
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
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(key.equals(server.ud.getKey(server.gd.getGroupAdminUsername(grpName)))){
                            for(String user: server.gd.removeByGroupname(grpName)){
                                SelectionKey userKey = server.ud.getKey(user);
                                sendInfo(userKey, "The group "+ grpName+ " has been deleted");
                                sendGROUPS(userKey, server.gd.getGroups(user));
                            }
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
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(key, "Group "+ grpName + " does not exist or has been deleted");
                        } else if (server.gd.isInGroup(server.ud.getName(key),grpName)) {
                            sendGrpMsg(sender, grpName, msg);
                        } else {
                            sendInfo(key, "You are not a member of the group "+ grpName);
                        }
                        log(sender + " => " + grpName + ": " + msg);
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "USERS":
                    sendUSERS(key, server.ud.getRegisteredUsers());
                    break;
                case "GROUPS":
                    sendGROUPS(key, server.gd.getGroups(server.ud.getName(key)));
                    break;
                case "FILE":
                    if(msgParts.length>4) {
                        String receiver = msgParts[1];
                        SelectionKey receiverKey = server.ud.getKey(receiver);
                        if (receiverKey == null) {
                            sendInfo(key, "The username "+receiver+" is not registered");
                        } else {
                            sendFile(receiverKey, sender, msgParts[2], msgParts[3], msgParts[4]);
                            log(sender + " -FILE-> " + receiver + ": " + msgParts[2]);
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
                case "GFILE":
                    if(msgParts.length>4) {
                        String grpName = msgParts[1];
                        if(server.gd.groupExists(grpName)){
                            sendGFile(key,grpName, sender, msgParts[2], msgParts[3], msgParts[4]);
                            log(sender + " =FILE=> " + grpName + ": " + msgParts[2]);
                        } else {
                            sendInfo(key, grpName + " does not exist or has been deleted");
                        }
                    } else {
                        sendInfo(key, "Invalid command format");
                    }
                    break;
            }
        }
    }

    private void broadcastUserList(){
        String onlineUsers = server.ud.getRegisteredUsers();
        Iterator<SelectionKey> iter = server.ud.getAllOnlineKey().iterator();
        while(iter.hasNext()){
            SelectionKey currentKey = iter.next();
            sendUSERS(currentKey, onlineUsers);
        }
    }

    private void sendInfo(SelectionKey key, String info){
        server.send(key,"INFO$" + info + "##");
    }

    private void sendUSERS(SelectionKey key, String users){
        server.send(key,"USERS$" + users+"##");
    }

    private void sendGROUPS(SelectionKey key, String grps){
        server.send(key,"GROUPS$" + grps+"##");
    }

    private void sendFile(SelectionKey key, String sender, String filename, String bytes, String keyString){
        server.send(key, "FILE$" + sender + "$" + filename + "$" + bytes + "$" + keyString + "##");
    }

    private void sendGFile(SelectionKey key,String grpName, String sender, String filename, String bytes, String keyString){
        for(String member: server.gd.getUsernames(grpName)){
            SelectionKey memberKey = server.ud.getKey(member);
            if(memberKey != key) {
                server.send(memberKey, "GFILE$" + grpName + "$" + sender + "$" + filename + "$" + bytes + "$" + keyString + "##");
            }
        }
    }

    private void sendGrpMsg(String sender, String grpName, String msg){
        for(String member: server.gd.getUsernames(grpName)){
            SelectionKey memberKey = server.ud.getKey(member);
            server.send(memberKey, "GMSG$" + grpName + "$" + sender + "$" + msg + "##");
        }
    }

    private void sendMsg(String sender, SelectionKey receiverKey, String msg){
        server.send(receiverKey, "MSG$" + sender + "$" + msg + "##");
    }

    private void sendSelfMsg(String receiver, String sender, SelectionKey receiverKey, String msg){
        server.send(receiverKey, "SELF$" + receiver + "$" + sender + "$" + msg + "##");
    }
    
}
