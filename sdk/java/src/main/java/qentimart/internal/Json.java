package qentimart.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JSON 解析与序列化（零依赖）。解析结果类型：Map / List / String / Long / Double / Boolean / null。 */
public final class Json {
    private Json() {
    }

    public static Object parse(byte[] utf8) {
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.len) throw new IllegalArgumentException("JSON: trailing data at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] utf8) {
        return (Map<String, Object>) parse(utf8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        return (Map<String, Object>) parse(s);
    }

    static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void ws() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            if (pos >= len) throw err("unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return num();
            }
        }

        Map<String, Object> obj() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (pos >= len || s.charAt(pos) != '"') throw err("expected string key");
                String k = str();
                ws();
                if (pos >= len || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                ws();
                m.put(k, value());
                ws();
                if (pos >= len) throw err("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        List<Object> arr() {
            ArrayList<Object> a = new ArrayList<>();
            pos++;
            ws();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return a;
            }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (pos >= len) throw err("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return a;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        String str() {
            pos++; // 开引号
            StringBuilder b = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (pos >= len) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (pos + 4 > len) throw err("bad \\u escape");
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw err("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw err("unterminated string");
        }

        Object num() {
            int start = pos;
            boolean dbl = false;
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    dbl = true;
                    pos++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw err("invalid number");
            if (dbl) return Double.parseDouble(t);
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(t);
            }
        }

        void expect(String w) {
            if (!s.startsWith(w, pos)) throw err("expected " + w);
            pos += w.length();
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("JSON: " + m + " at " + pos);
        }
    }

    public static String write(Object v, boolean pretty) {
        StringBuilder b = new StringBuilder();
        write(b, v, pretty, 0);
        return b.toString();
    }

    public static byte[] writeBytes(Object v, boolean pretty) {
        return write(v, pretty).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, boolean pretty, int depth) {
        if (v == null) {
            b.append("null");
            return;
        }
        if (v instanceof String) {
            quote(b, (String) v);
            return;
        }
        if (v instanceof Boolean) {
            b.append(((Boolean) v) ? "true" : "false");
            return;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            b.append(v.toString());
            return;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                b.append(Long.toString((long) d));
            } else {
                b.append(v.toString());
            }
            return;
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, pretty, depth + 1);
                quote(b, e.getKey());
                b.append(':');
                if (pretty) b.append(' ');
                write(b, e.getValue(), pretty, depth + 1);
            }
            nl(b, pretty, depth);
            b.append('}');
            return;
        }
        if (v instanceof Iterable) {
            Iterator<Object> i = ((Iterable<Object>) v).iterator();
            if (!i.hasNext()) {
                b.append("[]");
                return;
            }
            b.append('[');
            boolean first = true;
            while (i.hasNext()) {
                if (!first) b.append(',');
                first = false;
                nl(b, pretty, depth + 1);
                write(b, i.next(), pretty, depth + 1);
            }
            nl(b, pretty, depth);
            b.append(']');
            return;
        }
        quote(b, v.toString());
    }

    private static void nl(StringBuilder b, boolean pretty, int depth) {
        if (!pretty) return;
        b.append('\n');
        for (int i = 0; i < depth; i++) b.append("  ");
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
