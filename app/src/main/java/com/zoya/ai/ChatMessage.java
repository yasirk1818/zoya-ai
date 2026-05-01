package com.zoya.ai;

public class ChatMessage {
    public static final int TYPE_USER = 0;
    public static final int TYPE_ZOYA = 1;

    private final String text;
    private final int type;

    public ChatMessage(String text, int type) {
        this.text = text;
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public int getType() {
        return type;
    }
}
