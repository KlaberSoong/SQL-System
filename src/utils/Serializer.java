package utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 行 ↔ 字节 序列化 / 反序列化。
 *
 * 编码规则（每个值前有 1 字节 null 标志：0=有值, 1=NULL；NULL 时跳过后续负载）：
 *   INT     : 4 字节有符号整数
 *   FLOAT   : 4 字节浮点
 *   BOOL    : 1 字节
 *   VARCHAR / CHAR / TEXT : [4 字节长度前缀][UTF-8 字节]
 *   DATE    : 4 字节 epoch-day（距 1970-01-01 的天数）
 *   DECIMAL : [4 字节长度前缀][UTF-8 的 BigDecimal.toString()]（自描述，无需列精度/标度）
 */
public final class Serializer {
    private Serializer() {
    }

    /** 把单个值按类型写入输出流（含 1 字节 null 标志）。 */
    public static void writeValue(DataOutputStream out, Object value, ColumnType type) throws IOException {
        if (value == null) {
            out.writeByte(1);
            return;
        }
        out.writeByte(0);
        switch (type) {
            case INT:
                out.writeInt((Integer) value);
                break;
            case FLOAT:
                out.writeFloat(((Number) value).floatValue());
                break;
            case BOOL:
                out.writeBoolean((Boolean) value);
                break;
            case VARCHAR:
            case CHAR:
            case TEXT:
                writeString(out, String.valueOf(value));
                break;
            case DATE:
                out.writeInt((int) ((LocalDate) value).toEpochDay());
                break;
            case DECIMAL:
                BigDecimal bd = value instanceof BigDecimal
                        ? (BigDecimal) value
                        : new BigDecimal(value.toString());
                writeString(out, bd.toString());
                break;
            default:
                throw new IOException("unknown column type: " + type);
        }
    }

    /** 从输入流按类型读取一个值（含 1 字节 null 标志）。 */
    public static Object readValue(DataInputStream in, ColumnType type) throws IOException {
        int flag = in.readByte();
        if (flag != 0) {
            return null;
        }
        switch (type) {
            case INT:
                return in.readInt();
            case FLOAT:
                return in.readFloat();
            case BOOL:
                return in.readBoolean();
            case VARCHAR:
            case CHAR:
            case TEXT:
                return readString(in);
            case DATE:
                return LocalDate.ofEpochDay(in.readInt());
            case DECIMAL:
                return new BigDecimal(readString(in));
            default:
                throw new IOException("unknown column type: " + type);
        }
    }

    /** 写入 [4 字节长度前缀][UTF-8 字节]。 */
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    /** 读取 [4 字节长度前缀][UTF-8 字节] 并还原为字符串。 */
    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 把一行值列表编码为字节数组。 */
    public static byte[] encodeRow(List<Object> values, List<ColumnType> types) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            for (int i = 0; i < values.size(); i++) {
                writeValue(out, values.get(i), types.get(i));
            }
        } catch (IOException e) {
            throw new DbException("encodeRow failed: " + e.getMessage());
        }
        return bos.toByteArray();
    }

    /** 把字节数组解码回一行值列表。 */
    public static List<Object> decodeRow(byte[] data, List<ColumnType> types) {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        List<Object> values = new ArrayList<>(types.size());
        try (DataInputStream in = new DataInputStream(bis)) {
            for (ColumnType t : types) {
                values.add(readValue(in, t));
            }
        } catch (IOException e) {
            throw new DbException("decodeRow failed: " + e.getMessage());
        }
        return values;
    }
}
