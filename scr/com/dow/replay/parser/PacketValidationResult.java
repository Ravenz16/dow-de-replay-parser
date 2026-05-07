package com.dow.replay.parser;

public class PacketValidationResult {

    public boolean valid;

    public String reason;

    public PacketValidationResult(boolean valid, String reason) {
        this.valid = valid;
        this.reason = reason;
    }

    public static PacketValidationResult ok() {
        return new PacketValidationResult(true, "OK");
    }

    public static PacketValidationResult fail(String reason) {
        return new PacketValidationResult(false, reason);
    }
}