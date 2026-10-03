package com.loombot.adapter;

import java.util.List;

/** 控制命令的统一返回。 */
public record AdapterControlResult(
        boolean ok, String message, List<AdapterConnectionStatus> connections) {

    public static AdapterControlResult ok(String message) {
        return new AdapterControlResult(true, message, List.of());
    }

    public static AdapterControlResult failed(String message) {
        return new AdapterControlResult(false, message, List.of());
    }
}
