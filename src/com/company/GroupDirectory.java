package com.company;

import java.util.HashMap;
import java.util.ArrayList;

public class GroupDirectory{
    private HashMap<String, ArrayList<String>> usernameToGroupnames = new HashMap<>();
    private HashMap<String, ArrayList<String>> groupnameToUsername = new HashMap<>();

    public synchronized void put(String username, String groupname) {
        if (!usernameToGroupnames.containsKey(username)) {
            usernameToGroupnames.put(username, new ArrayList<>());
        }
        usernameToGroupnames.get(username).add(groupname);

        if (!groupnameToUsername.containsKey(groupname)) {
            groupnameToUsername.put(groupname, new ArrayList<>());
        }
        groupnameToUsername.get(groupname).add(username);
    }

    public synchronized String getGroups(String username){
        if(!usernameToGroupnames.containsKey(username)){
            return "";
        }
        ArrayList<String> groupList = usernameToGroupnames.get(username);
        java.util.Collections.sort(groupList);
        return String.join(",",groupList);
    }

    public synchronized ArrayList<String> getUsernames(String groupname) {
        return groupnameToUsername.get(groupname);
    }

    public synchronized Boolean groupExists(String groupname){
        return groupnameToUsername.containsKey(groupname);
    }

    public synchronized Boolean usernameExists(String username) {
        return usernameToGroupnames.containsKey(username);
    }

        public synchronized Boolean isInGroup(String key, String groupname){
        return groupnameToUsername.get(groupname).contains(key);
    }

    public synchronized void removeFromGroup(String key, String groupname){
        if(groupExists(groupname) && isInGroup(key, groupname)) {
            groupnameToUsername.get(groupname).remove(key);
            if (groupnameToUsername.get(groupname).size() == 0) {
                groupnameToUsername.remove(groupname);
            }
            usernameToGroupnames.get(key).remove(groupname);
            if (usernameToGroupnames.get(key).size() == 0) {
                usernameToGroupnames.remove(key);
            }
        }
    }

    public synchronized void removeByUsername(String key) {
        ArrayList<String> itemsToRemove = usernameToGroupnames.remove(key);
        if(itemsToRemove == null ){
            return;
        }
        for (String item : itemsToRemove) {
            groupnameToUsername.get(item).remove(key);
            if(groupnameToUsername.get(item).size()==0){
                groupnameToUsername.remove(item);
            }
        }
    }

    public synchronized ArrayList<String> removeByGroupname(String groupname) {
        ArrayList<String> itemsToRemove = groupnameToUsername.remove(groupname);
        if(itemsToRemove == null){
            return new ArrayList<String>();
        }
        for (String item : itemsToRemove) {
            usernameToGroupnames.get(item).remove(groupname);
            if(usernameToGroupnames.get(item).size()==0){
                usernameToGroupnames.remove(item);
            }
        }
        return itemsToRemove;
    }

    public synchronized String getGroupAdminUsername(String groupname){
        return groupnameToUsername.get(groupname).get(0);
    }
}