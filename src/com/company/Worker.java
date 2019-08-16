package com.company;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

public class Worker implements Runnable {
    private static final Logger logger = Logger.getLogger(Worker.class.getName());

    private Server server;
    Worker(Server server){
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
            if(!server.ud.isKeyOnline(key)){
                if(msgParts[0].equals("LOGIN")){
                    if (server.ud.namePresent(msgParts[1])) {
                        server.sendKey(key, "INFO$The name is already used. Please use another name.##");
                    } else {
                        server.ud.put(msgParts[1], key);
                        broadcastUserList();
                        server.sendKey(key, "INFO$Logged in as " + msgParts[1]+"##");
                        log(msgParts[1] + " Logged in from " + client.getRemoteAddress());
                        if(server.hasUnsentMessages(msgParts[1])){
                            server.sendUnsentMessages(msgParts[1]);
                        }
                        if(server.gd.usernameExists(msgParts[1])){
                            sendGROUPS(msgParts[1], server.gd.getGroups(msgParts[1]));
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
                        if (!server.ud.isRegistered(msgParts[1])) {
                            sendInfo(sender, "The username "+msgParts[1]+" is not registered");
                        } else {
                            log("Debug: receiver : "+receiver);
                            sendMsg(sender, receiver, msgParts[2]);
                            if(!receiver.equals(sender)) {
                                log("Debug: inside self msg");
                                sendSelfMsg(receiver, sender, msgParts[2]);
                            }
                            log(sender + " -> " + receiver + ": " + msgParts[2]);
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GCREATE":
                    //Creates a group and adds the sender in the group
                    if(msgParts.length>1){
                        String grpName = msgParts[1];
                        if(server.gd.groupExists(grpName)){
                            sendInfo(sender,"Group name already in use. Please select a different name");
                        } else {
                            server.gd.put(sender, grpName);
                            sendGROUPS(sender, server.gd.getGroups(sender));
                            sendInfo(sender, "Group "+grpName+" has been created");
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GADD":
                    //Checks if the sender is admin and the user is online and adds in the group
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(sender, "Group " + grpName + " not found");
                        } else if(!sender.equals(server.gd.getGroupAdminUsername(grpName))){
                            sendInfo(sender, "Only Group Admin can add users");
                        }else {
                            String members = msgParts[2];
                            for (String member : members.split(",")) {
                                if (!server.ud.namePresent(member)) {
                                    sendInfo(sender, "User " + member + " is not online");
                                } else {
                                    if (!server.gd.isInGroup(member, grpName)) {
                                        server.gd.put(member, grpName);
                                        sendInfo(sender, "User " + member + " is added to the group " + grpName);
                                        sendInfo(member, "You have been added to the group " + grpName);
                                        sendGROUPS(member, server.gd.getGroups(member));
                                    } else {
                                        sendInfo(sender, "User " + member + " is already in the group " + grpName);
                                    }
                                }
                            }

                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GREMOVE":
                    //Checks if the user is admin or if the user wants to remove himself from a group and removes the user
                    //also delete the group if no one is in the group
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        String users = msgParts[2];
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(sender, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(sender.equals(server.gd.getGroupAdminUsername(grpName)) || (users.split(",").length==1 && users.split(",")[0].equals(sender))){
                            for(String user: users.split(",")){
                                server.gd.removeFromGroup(user, grpName);
                                sendInfo(sender, "User "+ user + " has been removed");
                                sendInfo(user, "You have been removed from the group "+ grpName);
                                sendGROUPS(user,server.gd.getGroups(user));
                            }
                        } else {
                            sendInfo(sender, "You are not permitted to remove users from this group");
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GDELETE":
                    //Checks if user is admin and deletes the group
                    if(msgParts.length>1){
                        String grpName = msgParts[1];
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(sender, "Group "+ grpName + " does not exist or has been deleted");
                        } else if(sender.equals(server.gd.getGroupAdminUsername(grpName))){
                            for(String user: server.gd.removeByGroupname(grpName)){
                                sendInfo(user, "The group "+ grpName+ " has been deleted");
                                sendGROUPS(user, server.gd.getGroups(user));
                            }
                        } else {
                            sendInfo(sender, "You are not permitted to delete this group");
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GMSG":
                    //Check if the group exists and also if the sender is a part of the group and Sends a group message
                    if(msgParts.length>2){
                        String grpName = msgParts[1];
                        String msg = msgParts[2];
                        if(!server.gd.groupExists(grpName)){
                            sendInfo(sender, "Group "+ grpName + " does not exist or has been deleted");
                        } else if (server.gd.isInGroup(sender,grpName)) {
                            sendGrpMsg(sender, grpName, msg);
                        } else {
                            sendInfo(sender, "You are not a member of the group "+ grpName);
                        }
                        log(sender + " => " + grpName + ": " + msg);
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "USERS":
                    sendUSERS(sender, server.ud.getRegisteredUsers());
                    break;
                case "GROUPS":
                    sendGROUPS(sender, server.gd.getGroups(sender));
                    break;
                case "FILE":
                    if(msgParts.length>4) {
                        String receiver = msgParts[1];
                        if (!server.ud.isRegistered(receiver)) {
                            sendInfo(sender, "The username "+receiver+" is not registered");
                        } else {
                            sendFile(receiver, sender, msgParts[2], msgParts[3], msgParts[4]);
                            log(sender + " -FILE-> " + receiver + ": " + msgParts[2]);
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
                case "GFILE":
                    if(msgParts.length>4) {
                        String grpName = msgParts[1];
                        if(server.gd.groupExists(grpName)){
                            sendGFile(grpName, sender, msgParts[2], msgParts[3], msgParts[4]);
                            log(sender + " =FILE=> " + grpName + ": " + msgParts[2]);
                        } else {
                            sendInfo(sender, grpName + " does not exist or has been deleted");
                        }
                    } else {
                        sendInfo(sender, "Invalid command format");
                    }
                    break;
            }
        }
    }

    private void broadcastUserList(){
        String onlineUsers = server.ud.getRegisteredUsers();
        for (SelectionKey currentKey : server.ud.getAllOnlineKey()) {
            sendUSERS(server.ud.getName(currentKey), onlineUsers);
        }
    }

    private void sendInfo(String receiver, String info){
        server.send(receiver,"INFO$" + info + "##");
    }

    private void sendUSERS(String receiver, String users){
        server.send(receiver,"USERS$" + users+"##");
    }

    private void sendGROUPS(String receiver , String grps){
        server.send(receiver,"GROUPS$" + grps+"##");
    }

    private void sendFile(String receiver, String sender, String filename, String bytes, String keyString){
        server.send(receiver, "FILE$" + sender + "$" + filename + "$" + bytes + "$" + keyString + "##");
    }

    private void sendGFile(String grpName, String sender, String filename, String bytes, String keyString){
        for(String member: server.gd.getUsernames(grpName)){
            if(!member.equals(sender)) {
                server.send(member, "GFILE$" + grpName + "$" + sender + "$" + filename + "$" + bytes + "$" + keyString + "##");
            }
        }
    }

    private void sendGrpMsg(String sender, String grpName, String msg){
        for(String member: server.gd.getUsernames(grpName)){
            log("Debug: SendGrpMsg " + member);
            server.send(member, "GMSG$" + grpName + "$" + sender + "$" + msg + "##");
        }
    }

    private void sendMsg(String sender, String receiver , String msg){
        server.send(receiver , "MSG$" + sender + "$" + msg + "##");
    }

    private void sendSelfMsg(String receiver, String sender, String msg){
        server.send(sender, "SELF$" + receiver + "$" + sender + "$" + msg + "##");
    }
    
}
