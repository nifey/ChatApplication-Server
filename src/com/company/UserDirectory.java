package com.company;

import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

public class UserDirectory {
    private HashMap<String, SelectionKey> nameToKey;
    private HashMap<SelectionKey, String> keyToName;
    private HashMap<String, SelectionKey> offlineNameToKey;
    private HashMap<SelectionKey, String> offlineKeyToName;

    public UserDirectory(){
        nameToKey = new HashMap<String, SelectionKey>();
        keyToName = new HashMap<SelectionKey, String>();
        offlineNameToKey = new HashMap<String, SelectionKey>();
        offlineKeyToName = new HashMap<SelectionKey, String>();
    }

    public synchronized String getRegisteredUsers(){
        Iterator<String> iter = nameToKey.keySet().iterator();
        ArrayList<String> userList = new ArrayList<String>();
        while(iter.hasNext()){
            userList.add(iter.next());
        }
        Iterator<String> offlineIter = offlineNameToKey.keySet().iterator();
        while(offlineIter.hasNext()){
            userList.add(offlineIter.next());
        }
        java.util.Collections.sort(userList);
        return String.join(",",userList);
    }

    public synchronized Boolean namePresent(String name){
        return nameToKey.containsKey(name);
    }

    public synchronized Boolean isKeyOnline(SelectionKey key){ return keyToName.containsKey(key);}

    public synchronized Boolean isOnline(String username){
        return nameToKey.containsKey(username);
    }

    public synchronized String getName(SelectionKey key) {
        if(keyToName.containsKey(key)) {
            return keyToName.get(key);
        } else {
            return offlineKeyToName.get(key);
        }
    }

    public synchronized SelectionKey getKey(String name) {
        SelectionKey key = nameToKey.get(name);
        if(key == null){
            key = offlineNameToKey.get(name);
        }
        return key;
    }

    public synchronized Boolean isRegistered(String name){
        return nameToKey.containsKey(name) || offlineNameToKey.containsKey(name);
    }

    public synchronized Set<SelectionKey> getAllOnlineKey(){
        return keyToName.keySet();
    }

    public synchronized void put(String name, SelectionKey key){
        if(!nameToKey.containsKey(name) && !keyToName.containsKey(key)){
            nameToKey.put(name, key);
            keyToName.put(key, name);
            if(offlineNameToKey.containsKey(name)){
                SelectionKey oldKey = offlineNameToKey.get(name);
                offlineNameToKey.remove(name);
                offlineKeyToName.remove(oldKey);
            }
        }
    }

    public synchronized void deleteKey(SelectionKey key){
        if(keyToName.containsKey(key)){
            String name = keyToName.get(key);
            offlineKeyToName.put(key, name);
            offlineNameToKey.put(name, key);
            keyToName.remove(key);
            nameToKey.remove(name);
        }
    }

}