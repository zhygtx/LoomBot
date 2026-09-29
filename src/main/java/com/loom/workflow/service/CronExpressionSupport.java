package com.loom.workflow.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cron 表达式校验：与适配器层的求值实现保持同一套规则。
 *
 * <p>5 或 6 字段（秒 分 时 日 月 周），`?` 等价 `*`，支持列表、区间和步长；周字段 1-7 且 1 表示周日。
 */
public final class CronExpressionSupport {

    private CronExpressionSupport() {}

    private record Field(int minimum, int maximum) {}

    private static final List<Field> FIELDS =
            List.of(
                    new Field(0, 59),
                    new Field(0, 59),
                    new Field(0, 23),
                    new Field(1, 31),
                    new Field(1, 12),
                    new Field(1, 7));

    /** 校验表达式；非法直接抛 IllegalArgumentException。 */
    public static void validate(String expression) {
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Cron 表达式不能为空");
        }
        List<String> parts = new ArrayList<>(List.of(text.split("\\s+")));
        if (parts.size() == 5) {
            parts.add(0, "0");
        }
        if (parts.size() != 6) {
            throw new IllegalArgumentException("Cron 表达式必须是 5 或 6 字段");
        }
        for (int index = 0; index < parts.size(); index++) {
            parseField(parts.get(index), FIELDS.get(index));
        }
    }

    private static void parseField(String raw, Field field) {
        for (String piece : raw.split(",")) {
            String part = piece.strip();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Cron 字段为空");
            }
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                String stepText = part.substring(slash + 1);
                part = part.substring(0, slash);
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Cron 步长非法: " + stepText);
                }
                if (step <= 0) {
                    throw new IllegalArgumentException("Cron 步长必须是正整数");
                }
            }
            int start;
            int end;
            if (part.equals("*") || part.equals("?")) {
                start = field.minimum();
                end = field.maximum();
            } else if (part.contains("-")) {
                String[] range = part.split("-", 2);
                start = parseInt(range[0]);
                end = parseInt(range[1]);
            } else {
                start = end = parseInt(part);
            }
            if (start < field.minimum() || end > field.maximum() || start > end) {
                throw new IllegalArgumentException(
                        "Cron 字段超出范围 [" + field.minimum() + ", " + field.maximum() + "]: " + part);
            }
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cron 字段必须是数字: " + value);
        }
    }
}
