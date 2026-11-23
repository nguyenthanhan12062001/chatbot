package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.text.DecimalFormat;

/**
 * Pure Java recursive-descent mathematical expression evaluator (no script engine or shell).
 */
public class CalculatorTool implements AiTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "calculator",
            "Evaluates arithmetic expressions (+, -, *, /, ^, parentheses, sqrt, abs, sin, cos).",
            "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}}}",
            ToolPermission.AUTO_ALLOW
    );

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        String expr = (arguments != null && arguments.has("expression"))
                ? arguments.get("expression").getAsString()
                : "";
        if (expr == null || expr.trim().isEmpty()) {
            return new ToolResult(DEF.getName(), false, "", "Missing 'expression' parameter");
        }
        try {
            double val = evaluateExpression(expr.trim());
            DecimalFormat df = new DecimalFormat("0.##########");
            return new ToolResult(DEF.getName(), true, expr.trim() + " = " + df.format(val), "");
        } catch (Exception e) {
            return new ToolResult(DEF.getName(), false, "", "Invalid expression: " + e.getMessage());
        }
    }

    public static double evaluateExpression(String str) {
        return new Object() {
            int pos = -1;
            int ch;

            void nextChar() {
                ch = (++pos < str.length()) ? str.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < str.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) {
                        double denom = parseFactor();
                        if (denom == 0.0) throw new ArithmeticException("Division by zero");
                        x /= denom;
                    } else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Missing ')'");
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(str.substring(startPos, this.pos));
                } else if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') {
                    while ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) nextChar();
                    String func = str.substring(startPos, this.pos).toLowerCase();
                    if ("pi".equals(func)) {
                        x = Math.PI;
                    } else if ("e".equals(func)) {
                        x = Math.E;
                    } else {
                        x = parseFactor();
                        switch (func) {
                            case "sqrt": x = Math.sqrt(x); break;
                            case "abs": x = Math.abs(x); break;
                            case "sin": x = Math.sin(Math.toRadians(x)); break;
                            case "cos": x = Math.cos(Math.toRadians(x)); break;
                            case "tan": x = Math.tan(Math.toRadians(x)); break;
                            default: throw new RuntimeException("Unknown function: " + func);
                        }
                    }
                } else {
                    throw new RuntimeException("Unexpected: " + (char) ch);
                }
                if (eat('^')) x = Math.pow(x, parseFactor());
                return x;
            }
        }.parse();
    }
}
