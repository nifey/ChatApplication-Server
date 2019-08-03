package com.company;

import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.ArrayList;

public class GroupDirectory{
    private HashMap<SelectionKey, ArrayList<String>> keyToGroupnames = new HashMap<>();
    private HashMap<String, ArrayList<SelectionKey>> groupnameToKeys = new HashMap<>();

    public synchronized void put(SelectionKey key, String groupname) {
        if (!keyToGroupnames.containsKey(key)) {
            keyToGroupnames.put(key, new ArrayList<>());
        }
        keyToGroupnames.get(key).add(groupname);

        if (!groupnameToKeys.containsKey(groupname)) {
            groupnameToKeys.put(groupname, new ArrayList<>());
        }
        groupnameToKeys.get(groupname).add(key);
    }

    public synchronized String getGroups(SelectionKey key){
        if(!keyToGroupnames.containsKey(key)){
            return "";
        }
        ArrayList<String> groupList = keyToGroupnames.get(key);
        java.util.Collections.sort(groupList);
        return String.join(",",groupList);
    }

    public synchronized ArrayList<String> getGroupnames(SelectionKey key) {
        return keyToGroupnames.get(key);
    }

    public synchronized ArrayList<SelectionKey> getKeys(String groupname) {
        return groupnameToKeys.get(groupname);
    }

    public synchronized Boolean groupExists(String groupname){
        return groupnameToKeys.containsKey(groupname);
    }

    public synchronized Boolean isInGroup(SelectionKey key, String groupname){
        return groupnameToKeys.get(groupname).contains(key);
    }

    public synchronized void removeFromGroup(SelectionKey key, String groupname){
        if(groupExists(groupname) && isInGroup(key, groupname)) {
            groupnameToKeys.get(groupname).remove(key);
            if (groupnameToKeys.get(groupname).size() == 0) {
                groupnameToKeys.remove(groupname);
            }
            keyToGroupnames.get(key).remove(groupname);
            if (keyToGroupnames.get(key).size() == 0) {
                keyToGroupnames.remove(key);
            }
        }
    }

    public synchronized void removeByKey(SelectionKey key) {
        ArrayList<String> itemsToRemove = keyToGroupnames.remove(key);
        if(itemsToRemove == null ){
            return;
        }
        for (String item : itemsToRemove) {
            groupnameToKeys.get(item).remove(key);
            if(groupnameToKeys.get(item).size()==0){
                groupnameToKeys.remove(item);
            }
        }
    }

    public synchronized ArrayList<SelectionKey> removeByGroupname(String groupname) {
        ArrayList<SelectionKey> itemsToRemove = groupnameToKeys.remove(groupname);
        if(itemsToRemove == null){
            return new ArrayList<SelectionKey>();
        }
        for (SelectionKey item : itemsToRemove) {
            keyToGroupnames.get(item).remove(groupname);
            if(keyToGroupnames.get(item).size()==0){
                keyToGroupnames.remove(item);
            }
        }
        return itemsToRemove;
    }

    public synchronized SelectionKey getGroupAdminKey(String groupname){
        return groupnameToKeys.get(groupname).get(0);
    }
}