package utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 值 → 目标列类型的运行时转换（补编译期类型检查后的转换缺口），供 INSERT / UPDATE 共用。
 *
 * <p>规则：NULL 直通；数值族（INT/FLOAT/DECIMAL）互转（DECIMAL 按列标度舍入）；DATE 接受
 * {@link LocalDate} 或 ISO 字符串（如 {@code '2024-01-01'}）；CHAR 按列长补空格/截断；VARCHAR/TEXT
 * 接受字符串；其余原样返回。类型不匹配抛 {@link DbException}。
 */
public final class TypeCoercion {
    private TypeCoercion() {
    }

    public static Object coerce(Object value, ColumnDef target) {
        if (value == null) {
            return null;
        }
        switch (target.getType()) {
            case INT:
                return toInt(value, target);
            case FLOAT:
                return toFloat(value, target);
            case DECIMAL:
                return toDecimal(value, target);
            case DATE:
                return toDate(value, target);
            case CHAR:
                return toChar(value, target);
            case VARCHAR:
            case TEXT:
                return value instanceof String ? value : String.valueOf(value);
            case BOOL:
            default:
                return value;
        }
    }

    private static Object toInt(Object value, ColumnDef target) {
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).intValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new DbException("cannot assign " + value + " to column '" + target.getName()
                + "' of type " + target.getType().getKeyword());
    }

    private static Object toFloat(Object value, ColumnDef target) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        throw new DbException("cannot assign " + value + " to column '" + target.getName()
                + "' of type " + target.getType().getKeyword());
    }

    private static Object toDecimal(Object value, ColumnDef target) {
        BigDecimal bd;
        if (value instanceof BigDecimal) {
            bd = (BigDecimal) value;
        } else if (value instanceof Number) {
            bd = new BigDecimal(value.toString());
        } else if (value instanceof String) {
            bd = new BigDecimal((String) value);
        } else {
            throw new DbException("cannot assign " + value + " to column '" + target.getName()
                    + "' of type " + target.getType().getKeyword());
        }
        return bd.setScale(target.getScale(), RoundingMode.HALF_UP);
    }

    private static Object toDate(Object value, ColumnDef target) {
        if (value instanceof LocalDate) {
            return value;
        }
        if (value instanceof String) {
            try {
                return LocalDate.parse((String) value);
            } catch (DateTimeParseException e) {
                throw new DbException("invalid DATE literal '" + value
                        + "' for column '" + target.getName() + "' (expected yyyy-MM-dd)");
            }
        }
        throw new DbException("cannot assign " + value + " to column '" + target.getName()
                + "' of type " + target.getType().getKeyword());
    }

    private static Object toChar(Object value, ColumnDef target) {
        if (!(value instanceof String)) {
            throw new DbException("cannot assign " + value + " to column '" + target.getName()
                    + "' of type " + target.getType().getKeyword());
        }
        String s = (String) value;
        int n = target.getLength();
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
