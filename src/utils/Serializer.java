package utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 行 ↔ 字节 序列化 / 反序列化。
 *
 * 编码规则：
 *   INT     : 4 字节有符号整数
 *   FLOAT   : 4 字节浮点
 *   BOOL    : 1 字节
 *   VARCHAR : [4 字节长度前缀][UTF-8 字节]
 */
public final class Serializer {
    private Serializer() {
    }

    /** 把单个值按类型写入输出流。 */
    public static void writeValue(DataOutputStream out, Object value, ColumnType type) throws IOException {
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
                byte[] b = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
                break;
            default:
                throw new IOException("unknown column type: " + type);
        }
    }

    /** 从输入流按类型读取一个值。 */
    public static Object readValue(DataInputStream in, ColumnType type) throws IOException {
        switch (type) {
            case INT:
                return in.readInt();
            case FLOAT:
                return in.readFloat();
            case BOOL:
                return in.readBoolean();
            case VARCHAR:
                int len = in.readInt();
                byte[] b = new byte[len];
                in.readFully(b);
                return new String(b, StandardCharsets.UTF_8);
            default:
                throw new IOException("unknown column type: " + type);
        }
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
