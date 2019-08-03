package com.company;

import java.nio.channels.SelectionKey;

public class Task {
    private SelectionKey key;
    private String msg;
    public Task(SelectionKey key, String msg){
        this.key = key;
        this.msg = msg;
    }
    public SelectionKey getKey(){
        return this.key;
    }
    public String getMsg(){
        return this.msg;
    }
}
